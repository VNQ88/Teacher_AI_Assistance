#!/usr/bin/env bash
set -euo pipefail

RUN_ID="${1:-$(date +%Y%m%d-%H%M%S)}"
GOLDEN_FILE="${RETRIEVAL_EVAL_GOLDEN_FILE:-src/test/resources/eval/retrieval/golden-questions.jsonl}"

echo "Running retrieval eval snapshot with runId=${RUN_ID}"
RETRIEVAL_EVAL_RUN=true RETRIEVAL_EVAL_RUN_ID="${RUN_ID}" ./gradlew test \
  --rerun-tasks \
  --tests "com.example.teacherassistantai.eval.RetrievalEvalSnapshotTest" \
  -Dretrieval.eval.run=true \
  -Dretrieval.eval.runId="${RUN_ID}" \
  -Dretrieval.eval.goldenFile="${GOLDEN_FILE}"

echo "Retrieval eval snapshot generated at: output/retrieval-eval/${RUN_ID}"
