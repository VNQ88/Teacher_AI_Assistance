# TeacherAssistantAI JMeter tests

Artifacts:

- `teacher-assistant-ai-performance.jmx`: JMeter plan with profiles selected by `-JPROFILE`.
- `data/*.csv`: sample CSV inputs for users, subjects, documents, sessions, questions and upload files.
- `scripts/run-jmeter-performance.sh`: recommended runner.
- `scripts/seed-jmeter-data.sh`: optional API seed for subjects/chat sessions.

Recommended flow from repo root:

```bash
scripts/seed-jmeter-data.sh
scripts/run-jmeter-performance.sh smoke
scripts/run-jmeter-performance.sh baseline
scripts/run-jmeter-performance.sh storage
THREADS=1 scripts/run-jmeter-performance.sh rag
```

If local `jmeter` is not installed, set a Docker image:

```bash
JMETER_IMAGE=justb4/jmeter:latest scripts/run-jmeter-performance.sh smoke
```

Notes:

- `documents.csv` must contain real document IDs for baseline document-detail calls.
- RAG requires documents already processed with embeddings and available AI quota.
- Reports are written to `reports/performance/jmeter/<RUN_ID>/html/index.html`.
