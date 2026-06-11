#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/run-zap-safe-api-scan.sh

Runs a read-only OWASP ZAP API scan by:
1. Downloading the OpenAPI spec from $OPENAPI_URL or $BASE_URL/v3/api-docs.
2. Filtering the spec to GET, HEAD, and OPTIONS operations only.
3. Running zap-api-scan.py in safe mode against the filtered spec.

Environment:
  BASE_URL                         API base URL. Default: http://127.0.0.1:8080/api
  OPENAPI_URL                      OpenAPI URL. Default: $BASE_URL/v3/api-docs
  ZAP_IMAGE                        Docker image. Default: ghcr.io/zaproxy/zaproxy:stable
  ZAP_REPORT_DIR                   Report directory. Default: reports/security/zap
  ZAP_REPORT_PREFIX                Report file prefix. Default: zap-safe-api
  ZAP_DOCKER_NETWORK               Docker network for ZAP. Default: host
  ZAP_DOCKER_ADD_HOST_GATEWAY      Set true to map host.docker.internal to host-gateway.
  ZAP_ALLOW_REMOTE_SAFE_API_SCAN   Set true to allow non-local BASE_URL targets.
  ZAP_FAIL_ON_WARN                 Set true to fail on ZAP warning exit code 2.

Remote targets are blocked by default. Use zap-baseline.py for production
read-only checks, or set ZAP_ALLOW_REMOTE_SAFE_API_SCAN=true only for approved
staging/demo environments.
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

BASE_URL="${BASE_URL:-http://127.0.0.1:8080/api}"
BASE_URL="${BASE_URL%/}"
OPENAPI_URL="${OPENAPI_URL:-${BASE_URL}/v3/api-docs}"
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"
ZAP_REPORT_DIR="${ZAP_REPORT_DIR:-reports/security/zap}"
ZAP_REPORT_PREFIX="${ZAP_REPORT_PREFIX:-zap-safe-api}"
ZAP_DOCKER_NETWORK="${ZAP_DOCKER_NETWORK:-host}"
ZAP_DOCKER_ADD_HOST_GATEWAY="${ZAP_DOCKER_ADD_HOST_GATEWAY:-false}"
ZAP_ALLOW_REMOTE_SAFE_API_SCAN="${ZAP_ALLOW_REMOTE_SAFE_API_SCAN:-false}"
ZAP_FAIL_ON_WARN="${ZAP_FAIL_ON_WARN:-false}"

target_host="$(printf '%s' "${BASE_URL}" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:]+).*#\1#')"
case "${target_host}" in
  localhost|127.*|0.0.0.0|::1|app|teacher-assistant-ai|host.docker.internal)
    ;;
  *)
    if [[ "${ZAP_ALLOW_REMOTE_SAFE_API_SCAN}" != "true" ]]; then
      cat >&2 <<EOF
Refusing remote safe API scan for BASE_URL=${BASE_URL}.
Use the public baseline scan for production/read-only checks, or set
ZAP_ALLOW_REMOTE_SAFE_API_SCAN=true only for approved staging/demo targets.
EOF
      exit 2
    fi
    ;;
esac

mkdir -p "${ZAP_REPORT_DIR}"
report_dir_abs="$(cd "${ZAP_REPORT_DIR}" && pwd)"
raw_openapi="${report_dir_abs}/${ZAP_REPORT_PREFIX}-openapi-original.json"
safe_openapi="${report_dir_abs}/${ZAP_REPORT_PREFIX}-openapi-readonly.json"
scan_log="${report_dir_abs}/${ZAP_REPORT_PREFIX}-scan.log"
scan_exit_file="${report_dir_abs}/${ZAP_REPORT_PREFIX}-scan.exitcode"
json_report="${report_dir_abs}/${ZAP_REPORT_PREFIX}-report.json"
safe_openapi_container="/zap/wrk/$(basename "${safe_openapi}")"

echo "Fetching OpenAPI spec: ${OPENAPI_URL}"
curl --fail --silent --show-error --location "${OPENAPI_URL}" --output "${raw_openapi}"

echo "Writing read-only OpenAPI spec: ${safe_openapi}"
jq --arg baseUrl "${BASE_URL}" '
  def safe_method:
    (. | ascii_downcase) as $method
    | $method == "get" or $method == "head" or $method == "options";

  def keep_path_item:
    (.key | ascii_downcase) as $key
    | $key == "get"
      or $key == "head"
      or $key == "options"
      or $key == "$ref"
      or $key == "summary"
      or $key == "description"
      or $key == "parameters";

  .servers = [{"url": $baseUrl}]
  | .paths |= with_entries(
      .value |= with_entries(select(keep_path_item))
    )
  | .paths |= with_entries(
      select(
        [.value | to_entries[] | select(.key | safe_method)] | length > 0
      )
    )
' "${raw_openapi}" > "${safe_openapi}"

safe_operation_count="$(
  jq '[.paths[] | to_entries[] | select(.key | ascii_downcase as $method | $method == "get" or $method == "head" or $method == "options")] | length' "${safe_openapi}"
)"

if [[ "${safe_operation_count}" == "0" ]]; then
  echo "Filtered OpenAPI spec has no read-only operations." >&2
  exit 2
fi

echo "Read-only operations included: ${safe_operation_count}"

docker_args=(run --rm -v "${report_dir_abs}:/zap/wrk")
if [[ "${ZAP_DOCKER_NETWORK}" != "none" ]]; then
  docker_args+=(--network "${ZAP_DOCKER_NETWORK}")
fi
if [[ "${ZAP_DOCKER_ADD_HOST_GATEWAY}" == "true" ]]; then
  docker_args+=(--add-host "host.docker.internal:host-gateway")
fi

set +e
docker "${docker_args[@]}" "${ZAP_IMAGE}" \
  zap-api-scan.py \
  -t "${safe_openapi_container}" \
  -f openapi \
  -S \
  -r "${ZAP_REPORT_PREFIX}-report.html" \
  -w "${ZAP_REPORT_PREFIX}-report.md" \
  -J "${ZAP_REPORT_PREFIX}-report.json" \
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
