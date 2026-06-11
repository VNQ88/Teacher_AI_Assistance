#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
API_PREFIX="${API_PREFIX:-/api}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
STUDENT_EMAIL="${STUDENT_EMAIL:-student@example.com}"
STUDENT_PASSWORD="${STUDENT_PASSWORD:-student123}"
SUBJECT_COUNT="${SUBJECT_COUNT:-3}"
DATA_DIR="${DATA_DIR:-tests/jmeter/data}"
RUN_TAG="${RUN_TAG:-$(date +%Y%m%d%H%M%S)}"
API_BASE="${BASE_URL}${API_PREFIX}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

require_cmd curl
require_cmd jq
mkdir -p "$DATA_DIR"

login_response="$(curl -fsS \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  "$API_BASE/auth/login")"
access_token="$(printf '%s' "$login_response" | jq -r '.data.accessToken // empty')"
if [[ -z "$access_token" ]]; then
  echo "Could not extract access token from /auth/login response." >&2
  exit 1
fi

student_login_response="$(curl -fsS \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$STUDENT_EMAIL\",\"password\":\"$STUDENT_PASSWORD\"}" \
  "$API_BASE/auth/login")"
student_access_token="$(printf '%s' "$student_login_response" | jq -r '.data.accessToken // empty')"
if [[ -z "$student_access_token" ]]; then
  echo "Could not extract student access token from /auth/login response." >&2
  exit 1
fi

cat > "$DATA_DIR/admin-users.csv" <<CSV
email,password
$ADMIN_EMAIL,$ADMIN_PASSWORD
CSV

if [[ ! -s "$DATA_DIR/teacher-users.csv" ]]; then
  cat > "$DATA_DIR/teacher-users.csv" <<CSV
email,password
teacher@example.com,teacher123
CSV
fi

cat > "$DATA_DIR/student-users.csv" <<CSV
email,password
$STUDENT_EMAIL,$STUDENT_PASSWORD
CSV

cat > "$DATA_DIR/users.csv" <<CSV
email,password
$ADMIN_EMAIL,$ADMIN_PASSWORD
teacher@example.com,teacher123
$STUDENT_EMAIL,$STUDENT_PASSWORD
CSV

printf 'subjectId\n' > "$DATA_DIR/subjects.csv"
printf 'sessionId,sessionSubjectId\n' > "$DATA_DIR/chat-sessions.csv"

for i in $(seq 1 "$SUBJECT_COUNT"); do
  subject_payload="$(jq -n \
    --arg name "Perf Subject $RUN_TAG $i" \
    --arg code "P${RUN_TAG}${i}" \
    '{name:$name, code:$code, description:"Subject created for JMeter performance test", subjectType:"TEXT_BASED"}')"

  subject_response="$(curl -fsS \
    -H "Authorization: Bearer $access_token" \
    -H 'Content-Type: application/json' \
    -d "$subject_payload" \
    "$API_BASE/subjects")"
  subject_id="$(printf '%s' "$subject_response" | jq -r '.data.id')"
  printf '%s\n' "$subject_id" >> "$DATA_DIR/subjects.csv"

  session_payload="$(jq -n \
    --argjson subjectId "$subject_id" \
    --arg title "JMeter seeded session $RUN_TAG $i" \
    '{subjectId:$subjectId, title:$title}')"
  session_response="$(curl -fsS \
    -H "Authorization: Bearer $student_access_token" \
    -H 'Content-Type: application/json' \
    -d "$session_payload" \
    "$API_BASE/chat/sessions")"
  session_id="$(printf '%s' "$session_response" | jq -r '.data.id')"
  printf '%s,%s\n' "$session_id" "$subject_id" >> "$DATA_DIR/chat-sessions.csv"
done

cat > "$DATA_DIR/chat-questions.csv" <<'CSV'
question,topK,includeSources
"Ban chat cua nha nuoc gom nhung noi dung nao?",6,true
"Quy pham phap luat la gi?",6,true
CSV

first_subject_id="$(awk -F, 'NR==2 {print $1}' "$DATA_DIR/subjects.csv")"
cat > "$DATA_DIR/upload-files.csv" <<CSV
uploadFilePath,uploadSubjectId,uploadContentType
tests/jmeter/data/upload/sample-1kb.txt,$first_subject_id,text/plain
CSV

printf 'documentId,documentSubjectId\n' > "$DATA_DIR/documents.csv"
if documents_response="$(curl -fsS \
  -H "Authorization: Bearer $access_token" \
  "$API_BASE/documents?pageNo=0&pageSize=100" 2>/dev/null)"; then
  printf '%s' "$documents_response" | jq -r '.data.items[]? | [.id, .subjectId] | @csv' >> "$DATA_DIR/documents.csv"
fi

if [[ "$(wc -l < "$DATA_DIR/documents.csv")" -eq 1 ]]; then
  # Keep a placeholder so JMeter CSV Data Set Config can still initialize.
  printf '%s,%s\n' 1 "$first_subject_id" >> "$DATA_DIR/documents.csv"
  echo "No existing documents found. documents.csv contains a placeholder; baseline document-detail calls need real documents." >&2
fi

echo "Seeded JMeter data under $DATA_DIR"
echo "Subjects:      $DATA_DIR/subjects.csv"
echo "Chat sessions: $DATA_DIR/chat-sessions.csv"
echo "Documents:     $DATA_DIR/documents.csv"
