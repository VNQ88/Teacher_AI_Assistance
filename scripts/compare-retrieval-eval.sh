#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <before-run-id> <after-run-id> [report-file]" >&2
  exit 2
fi

BEFORE_RUN_ID="$1"
AFTER_RUN_ID="$2"
REPORT_FILE="${3:-}"
BASE_DIR="output/retrieval-eval"
BEFORE_REPORT="${BASE_DIR}/${BEFORE_RUN_ID}/report.json"
AFTER_REPORT="${BASE_DIR}/${AFTER_RUN_ID}/report.json"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to compare retrieval eval snapshots" >&2
  exit 2
fi

if [[ ! -f "${BEFORE_REPORT}" ]]; then
  echo "Missing before report: ${BEFORE_REPORT}" >&2
  exit 2
fi

if [[ ! -f "${AFTER_REPORT}" ]]; then
  echo "Missing after report: ${AFTER_REPORT}" >&2
  exit 2
fi

REPORT="$(
  jq -n -r \
    --slurpfile before "${BEFORE_REPORT}" \
    --slurpfile after "${AFTER_REPORT}" \
    --arg beforeRunId "${BEFORE_RUN_ID}" \
    --arg afterRunId "${AFTER_RUN_ID}" '
      def metric($r; $key): ($r.metrics[$key] // "null");
      def delta($key): "\(metric($before[0]; $key)) -> \(metric($after[0]; $key))";
      "# Retrieval Eval Comparison\n\n" +
      "- before: `\($beforeRunId)`\n" +
      "- after: `\($afterRunId)`\n\n" +
      "| metric | before -> after |\n" +
      "|---|---:|\n" +
      "| passRate | " + delta("passRate") + " |\n" +
	      "| selectedEvidenceHitRate | " + delta("selectedEvidenceHitRate") + " |\n" +
	      "| candidateEvidenceHitRate | " + delta("candidateEvidenceHitRate") + " |\n" +
	      "| selectedPathHitRate | " + delta("selectedPathHitRate") + " |\n" +
	      "| coarseEvaluated | " + delta("coarseEvaluated") + " |\n" +
	      "| selectedCoarsePathHitRate | " + delta("selectedCoarsePathHitRate") + " |\n" +
	      "| candidateCoarsePathHitRate | " + delta("candidateCoarsePathHitRate") + " |\n" +
	      "| avgSelectedEvidenceMrr | " + delta("avgSelectedEvidenceMrr") + " |\n" +
	      "| avgSelectedCoarseMrr | " + delta("avgSelectedCoarseMrr") + " |\n" +
	      "| avgLatencyMs | " + delta("avgLatencyMs") + " |\n" +
	      "| failed | " + delta("failed") + " |\n" +
      "| errored | " + delta("errored") + " |\n"
    '
)"

if [[ -n "${REPORT_FILE}" ]]; then
  mkdir -p "$(dirname "${REPORT_FILE}")"
  printf '%s\n' "${REPORT}" > "${REPORT_FILE}"
fi

printf '%s\n' "${REPORT}"
