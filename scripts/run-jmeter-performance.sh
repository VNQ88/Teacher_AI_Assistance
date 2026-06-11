#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/run-jmeter-performance.sh [profile]

Profiles:
  smoke     Validate login/token and basic authenticated reads.
  baseline  Read API baseline: current user, subjects, documents, chat history.
  auth      Login/refresh/current/logout loop.
  write     Light write mix: subject create/update + chat session create/close/delete.
  storage   Storage presign + 1KB multipart upload.
  rag       RAG chat low-concurrency flow.
  stress    Baseline read mix with higher default load.

Environment overrides:
  BASE_URL=http://127.0.0.1:8080
  API_PREFIX=/api
  THREADS=<number>
  RAMP_UP=<seconds>
  DURATION=<seconds>
  USERS_CSV=tests/jmeter/data/student-users.csv
  JMETER_IMAGE=justb4/jmeter:latest   # used only when local jmeter is not installed
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

PROFILE="${1:-smoke}"
case "$PROFILE" in
  smoke|baseline|auth|write|storage|rag|stress) ;;
  *)
    echo "Unknown profile: $PROFILE" >&2
    usage >&2
    exit 2
    ;;
esac

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
API_PREFIX="${API_PREFIX:-/api}"
JMX="${JMX:-tests/jmeter/teacher-assistant-ai-performance.jmx}"
REPORT_ROOT="${REPORT_ROOT:-reports/performance/jmeter}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)-$PROFILE}"
OUT_DIR="$REPORT_ROOT/$RUN_ID"
HTML_DIR="$OUT_DIR/html"
JTL_FILE="$OUT_DIR/results.jtl"
LOG_FILE="$OUT_DIR/jmeter.log"

parse_base_url() {
  local url="$1"
  PROTOCOL="${url%%://*}"
  local rest="${url#*://}"
  local host_port="${rest%%/*}"
  if [[ "$host_port" == *:* ]]; then
    HOST="${host_port%%:*}"
    PORT="${host_port##*:}"
  else
    HOST="$host_port"
    if [[ "$PROTOCOL" == "https" ]]; then
      PORT="443"
    else
      PORT="80"
    fi
  fi
}

parse_base_url "$BASE_URL"

case "$PROFILE" in
  smoke)
    THREADS="${THREADS:-3}"
    RAMP_UP="${RAMP_UP:-10}"
    DURATION="${DURATION:-300}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/student-users.csv}"
    ;;
  baseline)
    THREADS="${THREADS:-20}"
    RAMP_UP="${RAMP_UP:-60}"
    DURATION="${DURATION:-900}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/student-users.csv}"
    ;;
  auth)
    THREADS="${THREADS:-10}"
    RAMP_UP="${RAMP_UP:-30}"
    DURATION="${DURATION:-300}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/users.csv}"
    ;;
  write)
    THREADS="${THREADS:-5}"
    RAMP_UP="${RAMP_UP:-30}"
    DURATION="${DURATION:-300}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/teacher-users.csv}"
    ;;
  storage)
    THREADS="${THREADS:-5}"
    RAMP_UP="${RAMP_UP:-30}"
    DURATION="${DURATION:-300}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/teacher-users.csv}"
    ;;
  rag)
    THREADS="${THREADS:-1}"
    RAMP_UP="${RAMP_UP:-10}"
    DURATION="${DURATION:-300}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/student-users.csv}"
    ;;
  stress)
    THREADS="${THREADS:-100}"
    RAMP_UP="${RAMP_UP:-300}"
    DURATION="${DURATION:-2400}"
    USERS_CSV="${USERS_CSV:-tests/jmeter/data/student-users.csv}"
    ;;
esac

mkdir -p "$OUT_DIR"

declare -a JMETER_ARGS=(
  -n
  -t "$JMX"
  -l "$JTL_FILE"
  -j "$LOG_FILE"
  -e
  -o "$HTML_DIR"
  -JPROFILE="$PROFILE"
  -JPROTOCOL="$PROTOCOL"
  -JHOST="$HOST"
  -JPORT="$PORT"
  -JBASE_URL="$BASE_URL"
  -JAPI_PREFIX="$API_PREFIX"
  -JTHREADS="$THREADS"
  -JRAMP_UP="$RAMP_UP"
  -JDURATION="$DURATION"
  -JUSERS_CSV="$USERS_CSV"
  -JSUBJECTS_CSV="${SUBJECTS_CSV:-tests/jmeter/data/subjects.csv}"
  -JDOCUMENTS_CSV="${DOCUMENTS_CSV:-tests/jmeter/data/documents.csv}"
  -JCHAT_SESSIONS_CSV="${CHAT_SESSIONS_CSV:-tests/jmeter/data/chat-sessions.csv}"
  -JCHAT_QUESTIONS_CSV="${CHAT_QUESTIONS_CSV:-tests/jmeter/data/chat-questions.csv}"
  -JUPLOAD_FILES_CSV="${UPLOAD_FILES_CSV:-tests/jmeter/data/upload-files.csv}"
  -JTHINK_TIME_MS="${THINK_TIME_MS:-500}"
  -JTHINK_TIME_RANGE_MS="${THINK_TIME_RANGE_MS:-250}"
)

echo "Profile: $PROFILE"
echo "Target:  $PROTOCOL://$HOST:$PORT$API_PREFIX"
echo "Threads: $THREADS, ramp-up: ${RAMP_UP}s, duration: ${DURATION}s"
echo "Report:  $HTML_DIR/index.html"

if command -v jmeter >/dev/null 2>&1; then
  jmeter "${JMETER_ARGS[@]}"
elif [[ -n "${JMETER_IMAGE:-}" ]]; then
  docker run --rm --network host \
    -v "$PWD:/work" \
    -w /work \
    "$JMETER_IMAGE" \
    "${JMETER_ARGS[@]}"
else
  echo "JMeter is not installed and JMETER_IMAGE is not set." >&2
  echo "Install Apache JMeter or run: JMETER_IMAGE=justb4/jmeter:latest $0 $PROFILE" >&2
  exit 127
fi
