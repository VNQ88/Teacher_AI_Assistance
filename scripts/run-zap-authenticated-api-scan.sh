#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ZAP_AUTH_TOKEN=<token> ZAP_REPORT_PREFIX=zap-admin-api scripts/run-zap-authenticated-api-scan.sh

Runs an authenticated OWASP ZAP API active scan against a filtered OpenAPI spec.
By default, the filtered spec keeps only GET, HEAD, and OPTIONS operations so
ZAP can test authenticated read-only endpoints without creating/updating/deleting
data or queueing expensive jobs.

Environment:
  ZAP_AUTH_TOKEN                    Required bearer token.
  BASE_URL                          API base URL. Default: http://127.0.0.1:8080/api
  OPENAPI_URL                       OpenAPI URL. Default: $BASE_URL/v3/api-docs
  ZAP_IMAGE                         Docker image. Default: ghcr.io/zaproxy/zaproxy:stable
  ZAP_REPORT_DIR                    Report directory. Default: reports/security/zap
  ZAP_REPORT_PREFIX                 Report file prefix. Default: zap-auth-api
  ZAP_DOCKER_NETWORK                Docker network for ZAP. Default: host
  ZAP_DOCKER_ADD_HOST_GATEWAY       Set true to map host.docker.internal to host-gateway.
  ZAP_TIMEOUT                       OS timeout for one scan. Default: 10m
  ZAP_MAX_SCAN_MINUTES              ZAP active scan max duration. Default: 3
  ZAP_MAX_RULE_MINUTES              ZAP per-rule max duration. Default: 1
  ZAP_ALLOW_REMOTE_AUTH_SCAN        Set true to allow non-local BASE_URL targets.
  ZAP_INCLUDE_PRESIGN_PUT           Set true to include POST /storage/put.
  ZAP_FAIL_ON_WARN                  Set true to fail on ZAP warning exit code 2.

Remote authenticated scans are blocked by default. Enable them only for approved
staging/demo environments with test data and rollback/cleanup in place.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

require_command curl
require_command docker
require_command jq
require_command timeout

if [[ -z "${ZAP_AUTH_TOKEN:-}" || "${ZAP_AUTH_TOKEN:-}" == "null" ]]; then
  echo "ZAP_AUTH_TOKEN is required." >&2
  exit 2
fi

BASE_URL="${BASE_URL:-http://127.0.0.1:8080/api}"
BASE_URL="${BASE_URL%/}"
OPENAPI_URL="${OPENAPI_URL:-${BASE_URL}/v3/api-docs}"
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"
ZAP_REPORT_DIR="${ZAP_REPORT_DIR:-reports/security/zap}"
ZAP_REPORT_PREFIX="${ZAP_REPORT_PREFIX:-zap-auth-api}"
ZAP_DOCKER_NETWORK="${ZAP_DOCKER_NETWORK:-host}"
ZAP_DOCKER_ADD_HOST_GATEWAY="${ZAP_DOCKER_ADD_HOST_GATEWAY:-false}"
ZAP_TIMEOUT="${ZAP_TIMEOUT:-10m}"
ZAP_MAX_SCAN_MINUTES="${ZAP_MAX_SCAN_MINUTES:-3}"
ZAP_MAX_RULE_MINUTES="${ZAP_MAX_RULE_MINUTES:-1}"
ZAP_ALLOW_REMOTE_AUTH_SCAN="${ZAP_ALLOW_REMOTE_AUTH_SCAN:-false}"
ZAP_INCLUDE_PRESIGN_PUT="${ZAP_INCLUDE_PRESIGN_PUT:-false}"
ZAP_FAIL_ON_WARN="${ZAP_FAIL_ON_WARN:-false}"

target_host="$(printf '%s' "${BASE_URL}" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:]+).*#\1#')"
case "${target_host}" in
  localhost|127.*|0.0.0.0|::1|app|teacher-assistant-ai|host.docker.internal)
    ;;
  *)
    if [[ "${ZAP_ALLOW_REMOTE_AUTH_SCAN}" != "true" ]]; then
      cat >&2 <<EOF
Refusing remote authenticated API scan for BASE_URL=${BASE_URL}.
Set ZAP_ALLOW_REMOTE_AUTH_SCAN=true only for approved staging/demo targets.
EOF
      exit 2
    fi
    ;;
esac

mkdir -p "${ZAP_REPORT_DIR}"
report_dir_abs="$(cd "${ZAP_REPORT_DIR}" && pwd)"
raw_openapi="${report_dir_abs}/${ZAP_REPORT_PREFIX}-openapi-original.json"
filtered_openapi="${report_dir_abs}/${ZAP_REPORT_PREFIX}-openapi-filtered.json"
scan_log="${report_dir_abs}/${ZAP_REPORT_PREFIX}-scan.log"
scan_exit_file="${report_dir_abs}/${ZAP_REPORT_PREFIX}-scan.exitcode"
json_report="${report_dir_abs}/${ZAP_REPORT_PREFIX}-report.json"
filtered_openapi_container="/zap/wrk/$(basename "${filtered_openapi}")"

echo "Fetching OpenAPI spec: ${OPENAPI_URL}"
curl --fail --silent --show-error --location "${OPENAPI_URL}" --output "${raw_openapi}"

echo "Writing filtered authenticated OpenAPI spec: ${filtered_openapi}"
jq --arg baseUrl "${BASE_URL}" --arg includePresignPut "${ZAP_INCLUDE_PRESIGN_PUT}" '
  def included_operation($path; $method):
    ($method | ascii_downcase) as $m
    | $m == "get"
      or $m == "head"
      or $m == "options"
      or ($includePresignPut == "true" and $m == "post" and $path == "/storage/put");

  def keep_path_item($path):
    (.key | ascii_downcase) as $key
    | included_operation($path; $key)
      or $key == "$ref"
      or $key == "summary"
      or $key == "description"
      or $key == "parameters";

  .servers = [{"url": $baseUrl}]
  | .paths |= with_entries(
      .key as $path
      | .value |= with_entries(select(keep_path_item($path)))
    )
  | .paths |= with_entries(
      .key as $path
      | select(
          [.value | to_entries[] | select(included_operation($path; .key))] | length > 0
      )
    )
' "${raw_openapi}" > "${filtered_openapi}"

included_operation_count="$(
  jq --arg includePresignPut "${ZAP_INCLUDE_PRESIGN_PUT}" '
    def included_operation($path; $method):
      ($method | ascii_downcase) as $m
      | $m == "get"
        or $m == "head"
        or $m == "options"
        or ($includePresignPut == "true" and $m == "post" and $path == "/storage/put");

    [.paths | to_entries[] as $pathEntry
      | $pathEntry.value | to_entries[]
      | select(included_operation($pathEntry.key; .key))]
    | length
  ' "${filtered_openapi}"
)"

if [[ "${included_operation_count}" == "0" ]]; then
  echo "Filtered OpenAPI spec has no operations." >&2
  exit 2
fi

echo "Authenticated operations included: ${included_operation_count}"

docker_args=(run --rm -v "${report_dir_abs}:/zap/wrk")
if [[ "${ZAP_DOCKER_NETWORK}" != "none" ]]; then
  docker_args+=(--network "${ZAP_DOCKER_NETWORK}")
fi
if [[ "${ZAP_DOCKER_ADD_HOST_GATEWAY}" == "true" ]]; then
  docker_args+=(--add-host "host.docker.internal:host-gateway")
fi

set +e
timeout "${ZAP_TIMEOUT}" docker "${docker_args[@]}" "${ZAP_IMAGE}" \
  zap-api-scan.py \
  -t "${filtered_openapi_container}" \
  -f openapi \
  -r "${ZAP_REPORT_PREFIX}-report.html" \
  -w "${ZAP_REPORT_PREFIX}-report.md" \
  -J "${ZAP_REPORT_PREFIX}-report.json" \
  -z "-config replacer.full_list(0).description=auth-header \
      -config replacer.full_list(0).enabled=true \
      -config replacer.full_list(0).matchtype=REQ_HEADER \
      -config replacer.full_list(0).matchstr=Authorization \
      -config replacer.full_list(0).replacement=Bearer\\ ${ZAP_AUTH_TOKEN} \
      -config scanner.maxScanDurationInMins=${ZAP_MAX_SCAN_MINUTES} \
      -config scanner.maxRuleDurationInMins=${ZAP_MAX_RULE_MINUTES}" \
  2>&1 | tee "${scan_log}"
scan_exit="${PIPESTATUS[0]}"
set -e

printf '%s\n' "${scan_exit}" > "${scan_exit_file}"

if [[ -f "${json_report}" ]]; then
  high_or_critical_count="$(
    jq '[.site[]?.alerts[]? | select((.riskcode | tonumber) >= 3)] | length' "${json_report}"
  )"
else
  high_or_critical_count="unknown"
fi

echo "ZAP exit code: ${scan_exit}"
echo "High/Critical alerts: ${high_or_critical_count}"
echo "Reports written to: ${report_dir_abs}"

if [[ "${high_or_critical_count}" != "unknown" && "${high_or_critical_count}" != "0" ]]; then
  exit 1
fi

if [[ "${scan_exit}" == "0" ]]; then
  exit 0
fi

if [[ "${scan_exit}" == "2" && "${ZAP_FAIL_ON_WARN}" != "true" ]]; then
  echo "ZAP reported warnings only; not failing because ZAP_FAIL_ON_WARN is not true."
  exit 0
fi

exit "${scan_exit}"
