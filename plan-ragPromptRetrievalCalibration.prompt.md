## Plan: Optimize Prompt + Section-Accurate Retrieval

This plan is updated with your decisions: prompt text in English, model answers in Vietnamese, improved instruction quality, and relevance-based history capped at 5 messages (use 5 when enough relevant messages are available).

### Steps
1. Add retrieval metrics for section-based queries (e.g., "section 2") in `src/main/java/com/example/teacherassistantai/service/VectorRetrievalService.java` to track `Hit@K`, `MRR`, and section-mismatch rate.
2. Add an intent parser for "section/chapter/part + number" in `src/main/java/com/example/teacherassistantai/service/VectorRetrievalService.java` and pass soft/strict filters into `searchBySubjectVector` in `src/main/java/com/example/teacherassistantai/repository/DocumentChunkRepository.java`.
3. Redesign hybrid reranking in `src/main/java/com/example/teacherassistantai/service/VectorRetrievalService.java` using vector similarity + lexical overlap + numeric/section match + length prior, then cut to final `topK`.
4. Update history loading in `src/main/java/com/example/teacherassistantai/service/RagChatService.java` to relevance-first, recent-only, and select up to 5 messages (prefer exactly 5 when enough relevant history exists).
5. Optimize prompt construction in `src/main/java/com/example/teacherassistantai/service/RagPromptBuilderService.java`:
   - Prompt instructions are written in English for model clarity.
   - Output language is forced to Vietnamese.
   - Answer must be grounded in provided context and cite `[Chunk id]` for factual claims.
   - If evidence is insufficient, clearly say so and ask a targeted follow-up.
   - Avoid logging full prompt/context payloads.
6. Calibrate confidence in `src/main/java/com/example/teacherassistantai/service/RagConfidenceService.java` using retrieval-grounded signals; keep `MEDIUM >= 0.55` and add regression tests in `src/test/java/com/example/teacherassistantai/service`.

### Optimized Prompt Instruction (English -> Vietnamese Output)
Use this as the target instruction style for `RagPromptBuilderService`:

- You are a teaching assistant for students.
- Answer strictly from the provided context chunks.
- Respond in Vietnamese.
- For each key claim, cite supporting sources in the form `[Chunk <id>]`.
- If the question asks about a specific section/chapter/part number, prioritize that scope and do not mix unrelated sections.
- If context is insufficient or conflicting, state the limitation in Vietnamese and ask one concise clarification question.
- Do not fabricate facts, page numbers, or citations.
- Keep the answer concise, structured, and directly aligned with the user question.

### Locked Decisions
1. Prompt instructions: English.
2. Answer language: Vietnamese.
3. History relevance window: up to 5 messages, choose 5 when available.
4. Confidence threshold: `MEDIUM >= 0.55`.
