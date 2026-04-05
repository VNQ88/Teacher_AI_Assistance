## Plan: Chatbot Gia Su Theo Mon (RAG + Gemini)

Ban nhap ke hoach de ban review: trien khai chatbot hoi-dap theo mon hoc voi luu phien hoi thoai ben vung, RAG qua pgvector va LLM/Embedding dung Gemini. Ke hoach tan dung entity chat/document hien co, bo sung API chuan `ResponseData`, pipeline chunking-embedding dung rang buoc ban dua, co che xu ly loi nhat quan voi `GlobalExceptionHandler`, va chien luoc phan hoi khi do tin cay thap de giam hallucination.

### Steps
1. Chot kien truc domain va persistence quanh `ChatSession`/`ChatMessage`, them `ChatSessionRepository`, `ChatMessageRepository`, `AgentLogRepository`, va hoan thien truy van RAG trong `DocumentChunkRepository` voi filter bat buoc theo `subject_id` va uu tien `classroom_id` khi co.
2. Thiet ke API chat theo chuan `ResponseData` trong `ChatController`: `POST /chat/sessions`, `GET /chat/sessions`, `GET /chat/sessions/{id}`, `POST /chat/sessions/{id}/messages`, `GET /chat/sessions/{id}/messages`, voi DTO moi trong `src/main/java/com/example/teacherassistantai/dto/request/` va `src/main/java/com/example/teacherassistantai/dto/response/`.
3. Bo sung service dieu phoi hoi thoai trong `ChatService` va `RagOrchestratorService`: luu `USER` message, truy hoi chunk theo mon/lop, goi Gemini Flash Lite de sinh tra loi, luu `ASSISTANT` message kem `sourceChunks`, `tokensUsed`, `responseTimeMs`, `agentType=KNOWLEDGE_CHATBOT`.
4. Trien khai gateway Gemini moi trong `src/main/java/com/example/teacherassistantai/integration/gemini/` (`GeminiLlmGateway`, `GeminiEmbeddingGateway`, `GeminiProps`), cap nhat cau hinh o `application-dev.yml`, va noi pipeline embedding vao `DocumentProcessingService` de bo TODO chunking/embedding.
5. Ap dung chien luoc chunking dung tuyet doi trong `DocumentProcessingService` + utility moi `MarkdownChunkingService`: header-based sections tu `#` den `###`; target `800`, max `1500`, overlap `200`, min `500` (tru chunk cuoi section); table max `3200` va tach theo row; neu source `>100KB` thi force pre-split `1500` truoc khi chia theo header.
6. Hoan thien loi va low-confidence: dung `InvalidDataException`/`ResourceNotFoundException`/`RuntimeException` theo `GlobalExceptionHandler`, them metadata tin cay vao response (`confidenceScore`, `confidenceLevel`, `lowConfidenceReason`, `citations`) va quy tac fallback “khong du ngu canh” khi diem retrieval thap hoac thieu nguon; cap nhat schema SQL trong `src/main/resources/db/changes/` va include trong `src/main/resources/db/changelog/db.changelog-master.yaml`, dong thoi them test cho chunking/RAG/chat o `src/test/java/com/example/teacherassistantai/`.

### Further Considerations
1. Vector dimension cho Gemini Embedding 2: Option A giu `vector(1536)` voi `outputDimensionality=1536`; Option B migrate `vector(3072)` de giu toi da thong tin.
2. API gui message nen dong bo hay bat dong bo: Option A tra loi truc tiep mot luot; Option B tra `202` + polling de chiu tai tot hon.
3. Nguong low-confidence nen chot som: Option A 2 muc (NORMAL/LOW); Option B 3 muc (HIGH/MEDIUM/LOW) de UX mem hon.

