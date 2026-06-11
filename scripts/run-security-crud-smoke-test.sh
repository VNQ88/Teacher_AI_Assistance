#!/usr/bin/env bash
set -uo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/run-security-crud-smoke-test.sh

Runs controlled CRUD and authorization smoke tests against the API.
This script intentionally avoids AI, email, enrichment/backfill, document
processing, and real file upload endpoints.

Environment:
  BASE_URL              API base URL. Default: http://127.0.0.1:8080/api
  REPORT_DIR            Report directory. Default: reports/security/<timestamp>/crud-smoke-test
  ADMIN_EMAIL           Default: admin@example.com
  ADMIN_PASSWORD        Default: admin123
  TEACHER_EMAIL         Default: teacher@example.com
  TEACHER_PASSWORD      Default: teacher123
  STUDENT_EMAIL         Default: student@example.com
  STUDENT_PASSWORD      Default: student123

Outputs:
  crud-smoke-report.md
  crud-smoke-report.json
  crud-smoke-trace.log
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
require_command jq
require_command date
require_command mktemp

BASE_URL="${BASE_URL:-http://127.0.0.1:8080/api}"
BASE_URL="${BASE_URL%/}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
TEACHER_EMAIL="${TEACHER_EMAIL:-teacher@example.com}"
TEACHER_PASSWORD="${TEACHER_PASSWORD:-teacher123}"
STUDENT_EMAIL="${STUDENT_EMAIL:-student@example.com}"
STUDENT_PASSWORD="${STUDENT_PASSWORD:-student123}"

timestamp="$(date +%Y%m%d-%H%M%S)"
run_suffix="$(date +%H%M%S)"
REPORT_DIR="${REPORT_DIR:-reports/security/${timestamp}/crud-smoke-test}"
mkdir -p "${REPORT_DIR}"
report_dir_abs="$(cd "${REPORT_DIR}" && pwd)"

RESULTS_JSONL="${report_dir_abs}/crud-smoke-results.jsonl"
RESULTS_JSON="${report_dir_abs}/crud-smoke-report.json"
RESULTS_MD="${report_dir_abs}/crud-smoke-report.md"
TRACE_LOG="${report_dir_abs}/crud-smoke-trace.log"
TMP_DIR="$(mktemp -d)"

: > "${RESULTS_JSONL}"
: > "${TRACE_LOG}"

ADMIN_TOKEN=""
TEACHER_TOKEN=""
STUDENT_TOKEN=""
ADMIN_REFRESH_TOKEN=""
TEACHER_REFRESH_TOKEN=""
STUDENT_REFRESH_TOKEN=""
ADMIN_ID=""
STUDENT_ID=""
STUDENT_ORIGINAL_FULL_NAME=""
STUDENT_ORIGINAL_EMAIL=""
CREATED_USER_ID=""
CREATED_USER_DELETED="false"
SUBJECT_ID=""
SUBJECT_DELETED="false"
SESSION_ID=""
SESSION_DELETED="false"
LAST_RESPONSE=""
LAST_STATUS=""
LAST_PASS="false"

api_call() {
  local method="$1"
  local path="$2"
  local token="$3"
  local body="$4"
  local outfile="$5"

  local args=(-sS -o "${outfile}" -w "%{http_code}" -X "${method}" "${BASE_URL}${path}")
  if [[ -n "${token}" ]]; then
    args+=(-H "Authorization: Bearer ${token}")
  fi
  if [[ -n "${body}" ]]; then
    args+=(-H "Content-Type: application/json" --data "${body}")
  fi

  local status
  status="$(curl "${args[@]}" 2>>"${TRACE_LOG}")"
  local curl_exit=$?
  if [[ ${curl_exit} -ne 0 ]]; then
    printf '000'
    return 0
  fi
  printf '%s' "${status}"
}

body_message() {
  local body_file="$1"
  local message
  message="$(
    jq -r '
      if type == "object" then
        (.message // .error // .data.message? // empty)
      else
        empty
      end
    ' "${body_file}" 2>/dev/null
  )"
  if [[ -z "${message}" ]]; then
    message="$(head -c 160 "${body_file}" 2>/dev/null | tr '\n' ' ')"
  fi
  printf '%s' "${message}"
}

record_test() {
  local id="$1"
  local group="$2"
  local description="$3"
  local expected="$4"
  local actual="$5"
  local pass="$6"
  local note="$7"

  jq -n \
    --arg id "${id}" \
    --arg group "${group}" \
    --arg description "${description}" \
    --arg expected "${expected}" \
    --arg actual "${actual}" \
    --arg pass "${pass}" \
    --arg note "${note}" \
    '{
      id: $id,
      group: $group,
      description: $description,
      expected: $expected,
      actual: $actual,
      pass: ($pass == "true"),
      note: $note
    }' >> "${RESULTS_JSONL}"
}

token_for_label() {
  case "$1" in
    ADMIN) printf '%s' "${ADMIN_TOKEN}" ;;
    TEACHER) printf '%s' "${TEACHER_TOKEN}" ;;
    STUDENT) printf '%s' "${STUDENT_TOKEN}" ;;
    NONE) printf '' ;;
    *) printf '' ;;
  esac
}

run_test() {
  local id="$1"
  local group="$2"
  local description="$3"
  local method="$4"
  local path="$5"
  local token_label="$6"
  local expected_regex="$7"
  local body="${8:-}"

  local outfile="${TMP_DIR}/${id}.json"
  local token
  token="$(token_for_label "${token_label}")"
  local status
  status="$(api_call "${method}" "${path}" "${token}" "${body}" "${outfile}")"

  local pass="false"
  if [[ "${status}" =~ ^(${expected_regex})$ ]]; then
    pass="true"
  fi

  local note
  note="$(body_message "${outfile}")"
  record_test "${id}" "${group}" "${description}" "${expected_regex}" "${status}" "${pass}" "${note}"

  LAST_RESPONSE="${outfile}"
  LAST_STATUS="${status}"
  LAST_PASS="${pass}"

  if [[ "${pass}" == "true" ]]; then
    printf 'PASS %-9s %s -> %s\n' "${id}" "${description}" "${status}"
  else
    printf 'FAIL %-9s %s -> %s, expected %s\n' "${id}" "${description}" "${status}" "${expected_regex}"
  fi
}

login_role() {
  local id="$1"
  local role="$2"
  local email="$3"
  local password="$4"
  local body
  body="$(jq -nc --arg email "${email}" --arg password "${password}" '{email:$email,password:$password}')"

  local outfile="${TMP_DIR}/${id}.json"
  local status
  status="$(api_call "POST" "/auth/login" "" "${body}" "${outfile}")"

  local pass="false"
  if [[ "${status}" == "200" ]]; then
    pass="true"
  fi
  local note
  note="$(body_message "${outfile}")"
  record_test "${id}" "Auth" "Đăng nhập ${role}" "200" "${status}" "${pass}" "${note}"

  if [[ "${pass}" != "true" ]]; then
    printf 'FAIL %-9s Đăng nhập %s -> %s\n' "${id}" "${role}" "${status}"
    return 1
  fi

  local access_token refresh_token
  access_token="$(jq -r '.data.accessToken // empty' "${outfile}")"
  refresh_token="$(jq -r '.data.refreshToken // empty' "${outfile}")"
  if [[ -z "${access_token}" || -z "${refresh_token}" ]]; then
    record_test "${id}-TOKEN" "Auth" "Token ${role} tồn tại" "access+refresh token" "missing" "false" "Login response missing token"
    return 1
  fi

  case "${role}" in
    ADMIN)
      ADMIN_TOKEN="${access_token}"
      ADMIN_REFRESH_TOKEN="${refresh_token}"
      ;;
    TEACHER)
      TEACHER_TOKEN="${access_token}"
      TEACHER_REFRESH_TOKEN="${refresh_token}"
      ;;
    STUDENT)
      STUDENT_TOKEN="${access_token}"
      STUDENT_REFRESH_TOKEN="${refresh_token}"
      ;;
  esac

  printf 'PASS %-9s Đăng nhập %s -> %s\n' "${id}" "${role}" "${status}"
}

cleanup() {
  local cleanup_log="${report_dir_abs}/crud-smoke-cleanup.log"
  : > "${cleanup_log}"

  if [[ -n "${SESSION_ID}" && "${SESSION_DELETED}" != "true" && -n "${STUDENT_TOKEN}" ]]; then
    api_call "DELETE" "/chat/sessions/${SESSION_ID}" "${STUDENT_TOKEN}" "" "${TMP_DIR}/cleanup-session.json" >> "${cleanup_log}"
    printf ' cleanup session=%s\n' "${SESSION_ID}" >> "${cleanup_log}"
  fi

  if [[ -n "${STUDENT_ID}" && -n "${STUDENT_ORIGINAL_FULL_NAME}" && -n "${STUDENT_TOKEN}" ]]; then
    local restore_body
    restore_body="$(jq -nc \
      --arg fullName "${STUDENT_ORIGINAL_FULL_NAME}" \
      --arg email "${STUDENT_ORIGINAL_EMAIL}" \
      '{fullName:$fullName,email:$email}')"
    api_call "PUT" "/user/${STUDENT_ID}" "${STUDENT_TOKEN}" "${restore_body}" "${TMP_DIR}/cleanup-student.json" >> "${cleanup_log}"
    printf ' cleanup restore_student=%s\n' "${STUDENT_ID}" >> "${cleanup_log}"
  fi

  if [[ -n "${SUBJECT_ID}" && "${SUBJECT_DELETED}" != "true" ]]; then
    if [[ -n "${TEACHER_TOKEN}" ]]; then
      api_call "DELETE" "/subjects/${SUBJECT_ID}" "${TEACHER_TOKEN}" "" "${TMP_DIR}/cleanup-subject-teacher.json" >> "${cleanup_log}"
    fi
    if [[ -n "${ADMIN_TOKEN}" ]]; then
      api_call "DELETE" "/subjects/${SUBJECT_ID}" "${ADMIN_TOKEN}" "" "${TMP_DIR}/cleanup-subject-admin.json" >> "${cleanup_log}"
    fi
    printf ' cleanup subject=%s\n' "${SUBJECT_ID}" >> "${cleanup_log}"
  fi

  if [[ -n "${CREATED_USER_ID}" && "${CREATED_USER_DELETED}" != "true" && -n "${ADMIN_TOKEN}" ]]; then
    api_call "DELETE" "/user/${CREATED_USER_ID}" "${ADMIN_TOKEN}" "" "${TMP_DIR}/cleanup-user.json" >> "${cleanup_log}"
    printf ' cleanup user=%s\n' "${CREATED_USER_ID}" >> "${cleanup_log}"
  fi

  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

write_reports() {
  jq -s '
    {
      summary: {
        total: length,
        passed: ([.[] | select(.pass)] | length),
        failed: ([.[] | select(.pass | not)] | length)
      },
      tests: .
    }
  ' "${RESULTS_JSONL}" > "${RESULTS_JSON}"

  local total passed failed
  total="$(jq -r '.summary.total' "${RESULTS_JSON}")"
  passed="$(jq -r '.summary.passed' "${RESULTS_JSON}")"
  failed="$(jq -r '.summary.failed' "${RESULTS_JSON}")"

  {
    printf '# CRUD & Authorization Smoke Test\n\n'
    printf '%s\n' "- Date: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    printf '%s\n' "- Base URL: \`${BASE_URL}\`"
    printf '%s\n' "- Scope: controlled create/update/delete and authorization checks."
    printf '%s\n' "- Excluded: AI, email/OTP sending, enrichment/backfill, document processing, real file upload."
    printf '%s\n\n' "- Total: \`${total}\`, Passed: \`${passed}\`, Failed: \`${failed}\`"
    printf '## Test Results\n\n'
    printf '| ID | Nhóm | Kịch bản | Mong đợi | Thực tế | Kết quả | Ghi chú |\n'
    printf '| --- | --- | --- | --- | --- | --- | --- |\n'
    jq -r '
      .tests[]
      | [
          .id,
          .group,
          (.description | gsub("\\|"; "/")),
          .expected,
          .actual,
          (if .pass then "PASS" else "FAIL" end),
          (.note | gsub("\\|"; "/") | gsub("\\n"; " ") | .[0:180])
        ]
      | @tsv
    ' "${RESULTS_JSON}" | while IFS=$'\t' read -r id group desc expected actual result note; do
      printf '| `%s` | %s | %s | `%s` | `%s` | **%s** | %s |\n' \
        "${id}" "${group}" "${desc}" "${expected}" "${actual}" "${result}" "${note}"
    done
  } > "${RESULTS_MD}"
}

printf 'Running CRUD smoke test against %s\n' "${BASE_URL}"
printf 'Reports: %s\n' "${report_dir_abs}"

login_role "AUTH-01" "ADMIN" "${ADMIN_EMAIL}" "${ADMIN_PASSWORD}" || {
  write_reports
  exit 2
}
login_role "AUTH-02" "TEACHER" "${TEACHER_EMAIL}" "${TEACHER_PASSWORD}" || {
  write_reports
  exit 2
}
login_role "AUTH-03" "STUDENT" "${STUDENT_EMAIL}" "${STUDENT_PASSWORD}" || {
  write_reports
  exit 2
}

run_test "USER-00A" "User" "Lấy thông tin admin hiện tại" "GET" "/user/current" "ADMIN" "200"
ADMIN_ID="$(jq -r '.data.id // empty' "${LAST_RESPONSE}")"

run_test "USER-00S" "User" "Lấy thông tin student hiện tại" "GET" "/user/current" "STUDENT" "200"
STUDENT_ID="$(jq -r '.data.id // empty' "${LAST_RESPONSE}")"
STUDENT_ORIGINAL_FULL_NAME="$(jq -r '.data.fullName // empty' "${LAST_RESPONSE}")"
STUDENT_ORIGINAL_EMAIL="$(jq -r '.data.email // empty' "${LAST_RESPONSE}")"

created_email="sec-smoke-${run_suffix}@example.com"
created_password="SecSmoke123!"
create_user_body="$(jq -nc \
  --arg email "${created_email}" \
  --arg fullName "SEC Smoke Created User ${run_suffix}" \
  --arg password "${created_password}" \
  '{email:$email,fullName:$fullName,password:$password,role:"STUDENT"}')"
run_test "USER-01" "User" "Admin tạo user test" "POST" "/user" "ADMIN" "200|201" "${create_user_body}"
if [[ "${LAST_PASS}" == "true" ]]; then
  CREATED_USER_ID="$(jq -r '.data.id // empty' "${LAST_RESPONSE}")"
fi

login_created_body="$(jq -nc --arg email "${created_email}" --arg password "${created_password}" '{email:$email,password:$password}')"
run_test "USER-02" "User" "User do admin tạo đăng nhập được" "POST" "/auth/login" "NONE" "200" "${login_created_body}"

student_create_user_body="$(jq -nc \
  --arg email "sec-student-forbidden-${run_suffix}@example.com" \
  '{email:$email,fullName:"SEC Student Forbidden",password:"SecSmoke123!",role:"ADMIN"}')"
run_test "USER-03" "User" "Student không được tạo user" "POST" "/user" "STUDENT" "403" "${student_create_user_body}"

if [[ -n "${STUDENT_ID}" ]]; then
  student_update_self_body="$(jq -nc \
    --arg fullName "SEC Smoke Student ${run_suffix}" \
    --arg email "${STUDENT_ORIGINAL_EMAIL}" \
    '{fullName:$fullName,email:$email}')"
  run_test "USER-04" "User" "Student sửa profile của chính mình" "PUT" "/user/${STUDENT_ID}" "STUDENT" "200" "${student_update_self_body}"
else
  record_test "USER-04" "User" "Student sửa profile của chính mình" "200" "SKIP" "false" "Missing student id"
fi

if [[ -n "${ADMIN_ID}" ]]; then
  student_update_admin_body="$(jq -nc \
    --arg fullName "SEC Forbidden Admin Update ${run_suffix}" \
    --arg email "${ADMIN_EMAIL}" \
    '{fullName:$fullName,email:$email}')"
  run_test "USER-05" "User" "Student không được sửa user admin" "PUT" "/user/${ADMIN_ID}" "STUDENT" "403" "${student_update_admin_body}"
else
  record_test "USER-05" "User" "Student không được sửa user admin" "403" "SKIP" "false" "Missing admin id"
fi

subject_code="SEC${run_suffix}"
create_subject_body="$(jq -nc \
  --arg code "${subject_code}" \
  --arg name "SEC Smoke Subject ${run_suffix}" \
  '{name:$name,code:$code,description:"Temporary subject for CRUD security smoke test",subjectType:"TEXT_BASED"}')"
run_test "SUB-01" "Subject" "Teacher tạo subject test" "POST" "/subjects" "TEACHER" "200|201" "${create_subject_body}"
if [[ "${LAST_PASS}" == "true" ]]; then
  SUBJECT_ID="$(jq -r '.data.id // empty' "${LAST_RESPONSE}")"
fi

if [[ -n "${SUBJECT_ID}" ]]; then
  update_subject_body="$(jq -nc \
    --arg name "SEC Smoke Subject Updated ${run_suffix}" \
    '{name:$name,description:"Updated by CRUD smoke test",active:true,subjectType:"STEM"}')"
  run_test "SUB-02" "Subject" "Teacher sửa subject của mình" "PUT" "/subjects/${SUBJECT_ID}" "TEACHER" "200" "${update_subject_body}"
else
  record_test "SUB-02" "Subject" "Teacher sửa subject của mình" "200" "SKIP" "false" "Missing subject id"
fi

student_subject_body="$(jq -nc \
  --arg code "STU${run_suffix}" \
  '{name:"SEC Student Forbidden Subject",code:$code,description:"Should be forbidden",subjectType:"TEXT_BASED"}')"
run_test "SUB-03" "Subject" "Student không được tạo subject" "POST" "/subjects" "STUDENT" "403" "${student_subject_body}"

if [[ -n "${SUBJECT_ID}" ]]; then
  student_update_subject_body="$(jq -nc '{name:"SEC Student Forbidden Update",description:"Should be forbidden",active:true,subjectType:"TEXT_BASED"}')"
  run_test "SUB-04" "Subject" "Student không được sửa subject" "PUT" "/subjects/${SUBJECT_ID}" "STUDENT" "403" "${student_update_subject_body}"
else
  record_test "SUB-04" "Subject" "Student không được sửa subject" "403" "SKIP" "false" "Missing subject id"
fi

if [[ -n "${SUBJECT_ID}" ]]; then
  create_session_body="$(jq -nc --argjson subjectId "${SUBJECT_ID}" --arg title "SEC Smoke Session ${run_suffix}" '{subjectId:$subjectId,title:$title}')"
  run_test "CHAT-01" "ChatSession" "Student tạo chat session" "POST" "/chat/sessions" "STUDENT" "200|201" "${create_session_body}"
  if [[ "${LAST_PASS}" == "true" ]]; then
    SESSION_ID="$(jq -r '.data.id // empty' "${LAST_RESPONSE}")"
  fi
else
  record_test "CHAT-01" "ChatSession" "Student tạo chat session" "201" "SKIP" "false" "Missing subject id"
fi

if [[ -n "${SESSION_ID}" ]]; then
  run_test "CHAT-02" "ChatSession" "Student đóng session của mình" "PATCH" "/chat/sessions/${SESSION_ID}/close" "STUDENT" "200"
  run_test "CHAT-03" "ChatSession" "Teacher không đọc được session của student" "GET" "/chat/sessions/${SESSION_ID}" "TEACHER" "403|404"
  run_test "CHAT-04" "ChatSession" "Student xóa session của mình" "DELETE" "/chat/sessions/${SESSION_ID}" "STUDENT" "200"
  if [[ "${LAST_PASS}" == "true" ]]; then
    SESSION_DELETED="true"
  fi
else
  record_test "CHAT-02" "ChatSession" "Student đóng session của mình" "200" "SKIP" "false" "Missing session id"
  record_test "CHAT-03" "ChatSession" "Teacher không đọc được session của student" "403|404" "SKIP" "false" "Missing session id"
  record_test "CHAT-04" "ChatSession" "Student xóa session của mình" "200" "SKIP" "false" "Missing session id"
fi

if [[ -n "${SUBJECT_ID}" ]]; then
  run_test "SUB-05" "Subject" "Teacher xóa subject test" "DELETE" "/subjects/${SUBJECT_ID}" "TEACHER" "200"
  if [[ "${LAST_PASS}" == "true" ]]; then
    SUBJECT_DELETED="true"
  fi
else
  record_test "SUB-05" "Subject" "Teacher xóa subject test" "200" "SKIP" "false" "Missing subject id"
fi

doc_update_body="$(jq -nc '{title:"SEC Forbidden Document Update",description:"Should be forbidden"}')"
run_test "DOC-01" "Document" "Student không được sửa document metadata" "PATCH" "/documents/999999" "STUDENT" "403" "${doc_update_body}"
run_test "DOC-02" "Document" "Student không được xóa document" "DELETE" "/documents/999999" "STUDENT" "403"
run_test "DOC-03" "Document" "Teacher sửa document không tồn tại trả 404" "PATCH" "/documents/999999" "TEACHER" "404" "${doc_update_body}"

presign_body="$(jq -nc '{contentType:"image/png",ttlSeconds:60,resourceType:"OTHER"}')"
run_test "STO-01" "Storage" "Không token không được tạo presigned PUT" "POST" "/storage/put" "NONE" "403" "${presign_body}"
run_test "STO-02" "Storage" "Student tạo presigned PUT không upload file thật" "POST" "/storage/put" "STUDENT" "200" "${presign_body}"
invalid_presign_body="$(jq -nc '{contentType:"image/png",ttlSeconds:60}')"
run_test "STO-03" "Storage" "Presign PUT thiếu resourceType trả 400" "POST" "/storage/put" "STUDENT" "400" "${invalid_presign_body}"

long_name="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
long_subject_body="$(jq -nc \
  --arg name "${long_name}" \
  --arg code "LONG${run_suffix}" \
  '{name:$name,code:$code,description:"Too long name",subjectType:"TEXT_BASED"}')"
run_test "IN-01" "Input" "Subject name quá dài trả 400" "POST" "/subjects" "TEACHER" "400" "${long_subject_body}"

invalid_enum_body="$(jq -nc \
  --arg code "INV${run_suffix}" \
  '{name:"SEC Invalid Enum Subject",code:$code,description:"Invalid enum",subjectType:"INVALID"}')"
run_test "IN-02" "Input" "Invalid subjectType trả 400" "POST" "/subjects" "TEACHER" "400" "${invalid_enum_body}"
run_test "IN-03" "Input" "Path id bằng 0 trả 400" "GET" "/subjects/0" "TEACHER" "400"

if [[ -n "${CREATED_USER_ID}" ]]; then
  run_test "USER-06" "User" "Admin xóa user test" "DELETE" "/user/${CREATED_USER_ID}" "ADMIN" "200"
  if [[ "${LAST_PASS}" == "true" ]]; then
    CREATED_USER_DELETED="true"
  fi
else
  record_test "USER-06" "User" "Admin xóa user test" "200" "SKIP" "false" "Missing created user id"
fi

write_reports

failed_count="$(jq -r '.summary.failed' "${RESULTS_JSON}")"
passed_count="$(jq -r '.summary.passed' "${RESULTS_JSON}")"
total_count="$(jq -r '.summary.total' "${RESULTS_JSON}")"

printf '\nSummary: total=%s passed=%s failed=%s\n' "${total_count}" "${passed_count}" "${failed_count}"
printf 'Markdown report: %s\n' "${RESULTS_MD}"
printf 'JSON report: %s\n' "${RESULTS_JSON}"

if [[ "${failed_count}" != "0" ]]; then
  exit 1
fi

exit 0
