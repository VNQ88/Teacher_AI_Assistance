#!/usr/bin/env bash
set -euo pipefail

RUN_ID="${1:-$(date +%Y%m%d-%H%M%S)}"

echo "Running Phase 0 baseline snapshot with runId=${RUN_ID}"
PHASE0_RUN=true PHASE0_RUN_ID="${RUN_ID}" ./gradlew test \
  --rerun-tasks \
  --tests "com.example.teacherassistantai.phase0.DocumentPhase0BaselineSnapshotTest" \
  -Dphase0.run=true \
  -Dphase0.runId="${RUN_ID}"

echo "Phase 0 snapshot generated at: output/phase0-baseline/${RUN_ID}"
