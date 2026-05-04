# Precompute Enrichment Plan cho Hierarchical RAG

## 1. Mục tiêu

Triển khai bước precompute enrichment chạy riêng sau khi tài liệu upload được parse, chunk, embedding và chuyển sang trạng thái sẵn sàng hỏi đáp.

Các artifact cần sinh tự động cho từng tài liệu:

- Summary dạng đoạn văn ngắn cho các cấp hierarchy: `part`, `chapter`, `section`, `subsection`.
- Bộ câu hỏi ôn tập cho từng scope hierarchy, gồm:
  - Trắc nghiệm.
  - Đúng/sai.
  - Điền khuyết.
- Đáp án/gợi ý đáp án cho từng câu hỏi.
- Độ khó nếu khả thi: `EASY`, `MEDIUM`, `HARD`.
- Citation ngắn cho summary và câu hỏi.

Artifact dùng chung theo `document`, chưa tách theo `classroom`.

Model dùng cho enrichment: `openai-gpt-oss-120b`.

Regenerate chỉ chạy khi có yêu cầu rõ ràng, không tự động regenerate khi prompt/model đổi.

## 2. Quyết định đã chốt

| Vấn đề | Quyết định |
| --- | --- |
| Cấp hierarchy cần precompute | `part`, `chapter`, `section`, `subsection` |
| Thời điểm chạy | Tự động sau mỗi upload/document processing thành công |
| Format summary | Đoạn văn ngắn |
| Loại câu hỏi | Trắc nghiệm, đúng/sai, điền khuyết |
| Có đáp án/gợi ý | Có |
| Lưu câu hỏi vào QuestionBank | Chưa, tạm thời chỉ trả về trong chatbot |
| Số lượng câu hỏi mặc định | 15-20 câu |
| Độ khó | Có nếu khả thi |
| Citation | Citation ngắn |
| Chấp nhận cost/token lúc xử lý tài liệu | Có |
| Scope classroom | Chưa triển khai, artifact dùng chung theo document |
| Model | `openai-gpt-oss-120b` |
| Regenerate khi prompt/model đổi | Chỉ regenerate khi được yêu cầu |
| Admin/debug/retry | Có |
| `DocumentStatus.READY` | Sẵn sàng hỏi đáp RAG |
| `DocumentStatus.FULL_USE` | Sẵn sàng đầy đủ summary/câu hỏi |
| `enrichmentStatus` | Trạng thái chi tiết của quá trình tạo học liệu |

## 3. Nguyên tắc thiết kế

1. Không chặn luồng upload chính lâu hơn mức cần thiết.
   - `DocumentProcessingService` vẫn chịu trách nhiệm parse, chunk, embed.
   - Sau khi document chuyển `READY`, enqueue enrichment async.
   - Khi enrichment hoàn tất toàn bộ required artifacts, document chuyển `FULL_USE`.

2. Artifact không thay thế chunks gốc.
   - Summary và question set là dữ liệu tăng tốc/định hướng.
   - Khi chatbot trả lời hoặc tạo câu hỏi tùy biến, vẫn cần dùng chunks gốc trong scope để grounding.

3. Lưu artifact versioned.
   - Lưu `promptVersion`, `model`, `sourceHash`.
   - Nếu source không đổi và artifact đã `COMPLETED`, skip.
   - Nếu muốn regenerate, dùng endpoint force.

4. Tránh transaction dài khi gọi LLM.
   - DB transaction chỉ dùng khi đọc scope/lưu trạng thái/lưu kết quả.
   - LLM call nằm ngoài transaction dài.

5. Enrichment failure không làm document mất trạng thái `READY`.
   - Document vẫn dùng được cho RAG.
   - Artifact lỗi có status `FAILED`, có thể retry.
   - Nếu enrichment chỉ lỗi một phần, document vẫn giữ `READY`, còn `enrichmentStatus = PARTIAL_FAILED`.

6. Trạng thái hiển thị cho người dùng phải ngắn gọn và phân biệt rõ.
   - Hỏi đáp RAG sẵn sàng không đồng nghĩa học liệu enrichment đã sẵn sàng.
   - UI/API nên trả cả trạng thái kỹ thuật và label ngắn gọn.

## 4. Data Model

### 4.1. Cập nhật `documents`

Giữ `DocumentStatus.READY`, nhưng định nghĩa rõ:

- `READY`: sẵn sàng hỏi đáp RAG. Tài liệu đã parse/chunk/embed xong.
- `FULL_USE`: sẵn sàng đầy đủ học liệu. Tài liệu đã có đủ summary/câu hỏi required artifacts.

Thêm enum value `FULL_USE` vào `DocumentStatus` và cập nhật DB check constraint tương ứng.

Thêm trạng thái enrichment tổng hợp trên `documents` để UI không phải tự tính từ toàn bộ artifacts mỗi lần:

```sql
ALTER TABLE documents
    ADD COLUMN enrichment_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN enrichment_started_at TIMESTAMP,
    ADD COLUMN enrichment_completed_at TIMESTAMP,
    ADD COLUMN enrichment_error TEXT;
```

`enrichment_status` là trạng thái chi tiết của quá trình enrichment theo document, còn `document_node_artifacts.status` là trạng thái từng artifact.

### 4.2. Bảng `document_node_artifacts`

Đề xuất tạo bảng riêng:

```sql
CREATE TABLE document_node_artifacts
(
    id               BIGSERIAL PRIMARY KEY,
    document_id      BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    document_node_id BIGINT NOT NULL REFERENCES document_nodes(id) ON DELETE CASCADE,
    artifact_type    VARCHAR(40) NOT NULL,
    status           VARCHAR(30) NOT NULL,
    prompt_version   VARCHAR(80) NOT NULL,
    model            VARCHAR(120) NOT NULL,
    source_hash      VARCHAR(128) NOT NULL,
    content_jsonb    JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message    TEXT,
    token_count      INTEGER,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_node_artifacts_document_type_status
    ON document_node_artifacts(document_id, artifact_type, status);

CREATE INDEX idx_node_artifacts_node_type
    ON document_node_artifacts(document_node_id, artifact_type);

CREATE UNIQUE INDEX uk_node_artifact_version
    ON document_node_artifacts(document_node_id, artifact_type, prompt_version, model, source_hash);

CREATE INDEX idx_node_artifacts_content_gin
    ON document_node_artifacts USING gin(content_jsonb);
```

### 4.3. Enum và label đề xuất

`DocumentStatus`:

| Enum kỹ thuật | Label người dùng | Ý nghĩa |
| --- | --- | --- |
| `UPLOADED` | `Đã tải lên` | File gốc đã nằm trong storage |
| `PARSING` | `Đang đọc` | Đang parse nội dung tài liệu |
| `CHUNKING` | `Đang chia đoạn` | Đang tạo hierarchy/chunks |
| `EMBEDDING` | `Đang lập chỉ mục` | Đang tạo embedding |
| `READY` | `Sẵn sàng hỏi đáp` | Đã có chunks/embedding, chatbot RAG cơ bản dùng được |
| `FULL_USE` | `Đủ học liệu` | Đã có đủ summary/câu hỏi precomputed |
| `FAILED` | `Lỗi xử lý` | Parse/chunk/embed lỗi |

`DocumentEnrichmentStatus`:

| Enum kỹ thuật | Label người dùng | Ý nghĩa |
| --- | --- | --- |
| `NOT_STARTED` | `Chưa tạo học liệu` | Chưa bắt đầu enrichment |
| `QUEUED` | `Đang chờ` | Đã đưa vào queue |
| `RUNNING` | `Đang tạo học liệu` | Đang gọi LLM sinh summary/câu hỏi |
| `ENRICHED` | `Đủ học liệu` | Tất cả artifact bắt buộc đã xong |
| `PARTIAL_FAILED` | `Thiếu một phần` | Một phần artifact lỗi, phần còn lại dùng được |
| `FAILED` | `Lỗi học liệu` | Enrichment lỗi toàn bộ hoặc lỗi nghiêm trọng |
| `SKIPPED` | `Đã bỏ qua` | Enrichment bị tắt hoặc không đủ nội dung |

`DocumentNodeArtifactType`:

- `SUMMARY`
- `REVIEW_QUESTION_SET`

`DocumentNodeArtifactStatus`:

- `PENDING`
- `RUNNING`
- `COMPLETED`
- `FAILED`
- `SKIPPED`

### 4.4. Response status đề xuất

`DocumentResponse` nên trả thêm label ngắn gọn để UI/mobile không phải tự map enum:

```json
{
  "status": "READY",
  "statusLabel": "Sẵn sàng hỏi đáp",
  "enrichmentStatus": "RUNNING",
  "enrichmentStatusLabel": "Đang tạo học liệu",
  "ragReady": true,
  "learningMaterialsReady": false
}
```

Khi enrichment hoàn tất:

```json
{
  "status": "FULL_USE",
  "statusLabel": "Đủ học liệu",
  "enrichmentStatus": "ENRICHED",
  "enrichmentStatusLabel": "Đủ học liệu",
  "ragReady": true,
  "learningMaterialsReady": true
}
```

Ý nghĩa hiển thị:

- `statusLabel`: trạng thái tổng quan của tài liệu.
- `enrichmentStatusLabel`: trạng thái chi tiết của summary/câu hỏi.
- `ragReady`: frontend có thể bật chatbot hỏi đáp.
- `learningMaterialsReady`: frontend có thể bật nhanh chức năng tóm tắt/câu hỏi precomputed.

### 4.5. JSON cho `SUMMARY`

Vì yêu cầu summary dạng đoạn văn ngắn, JSON nên tối giản:

```json
{
  "nodeTitle": "Chương 2: ...",
  "sectionPath": "Phần I > Chương 2",
  "summary": "Đoạn văn ngắn tóm tắt nội dung chính...",
  "citations": [
    {
      "chunkId": 123,
      "pageFrom": 10,
      "pageTo": 12
    }
  ]
}
```

### 4.6. JSON cho `REVIEW_QUESTION_SET`

```json
{
  "nodeTitle": "Chương 2: ...",
  "sectionPath": "Phần I > Chương 2",
  "questionCount": 18,
  "questions": [
    {
      "type": "MULTIPLE_CHOICE",
      "difficulty": "MEDIUM",
      "question": "...",
      "options": [
        {"label": "A", "content": "..."},
        {"label": "B", "content": "..."},
        {"label": "C", "content": "..."},
        {"label": "D", "content": "..."}
      ],
      "correctAnswer": "B",
      "answerExplanation": "...",
      "citations": [
        {"chunkId": 123, "pageFrom": 10, "pageTo": 10}
      ]
    },
    {
      "type": "TRUE_FALSE",
      "difficulty": "EASY",
      "question": "...",
      "correctAnswer": true,
      "answerExplanation": "...",
      "citations": [
        {"chunkId": 124, "pageFrom": 11, "pageTo": 11}
      ]
    },
    {
      "type": "FILL_BLANK",
      "difficulty": "HARD",
      "question": "... ____ ...",
      "correctAnswer": "...",
      "answerExplanation": "...",
      "citations": [
        {"chunkId": 125, "pageFrom": 12, "pageTo": 13}
      ]
    }
  ]
}
```

## 5. Phase 1: Schema, Entity, Repository

### Mục tiêu

Tạo nền lưu artifact và truy vấn trạng thái.

### Việc cần làm

1. Thêm SQL vào `src/main/resources/db/current-sql.sql`.
2. Thêm migration script trong `src/main/resources/db/changes-in-past/`.
3. Thêm entity:
   - `DocumentNodeArtifact`.
4. Thêm enum:
   - `DocumentNodeArtifactType`.
   - `DocumentNodeArtifactStatus`.
5. Thêm repository:
   - `DocumentNodeArtifactRepository`.

### Repository methods cần có

- Find artifact theo `documentNodeId`, `artifactType`, `promptVersion`, `model`, `sourceHash`.
- Find artifacts theo `documentId`.
- Find failed artifacts theo `documentId`.
- Delete/regenerate artifacts theo document/node/type khi force regenerate.

### Acceptance criteria

- App compile.
- Có thể lưu/read artifact JSONB.
- Unique constraint chống duplicate artifact cùng version/source.

## 6. Phase 2: Scope Retrieval theo Hierarchy

### Mục tiêu

Lấy đầy đủ chunks thuộc một node hierarchy để LLM có đủ ngữ cảnh khi sinh summary/câu hỏi.

### Service đề xuất

`DocumentNodeScopeService`

Trách nhiệm:

1. Lấy descendants của một `DocumentNode`.
2. Lấy `DocumentChunk` thuộc node và descendants.
3. Sort theo `sourceOrder`.
4. Tính source hash từ:
   - `documentId`
   - `documentNodeId`
   - node `sectionPath`
   - chunk ids
   - chunk contents
   - chunk updated timestamps nếu có

### Cần bổ sung repository/query

Vì hiện có `document_nodes` dạng cây parent-child, cần query descendants.

Phương án 1: recursive CTE native query.

```sql
WITH RECURSIVE node_tree AS (
    SELECT id
    FROM document_nodes
    WHERE id = :rootNodeId
    UNION ALL
    SELECT child.id
    FROM document_nodes child
    JOIN node_tree parent ON child.parent_id = parent.id
)
SELECT *
FROM document_chunks
WHERE node_id IN (SELECT id FROM node_tree)
ORDER BY source_order ASC;
```

Phương án 2: lấy all nodes của document rồi duyệt cây trong Java.

Ưu tiên phương án 1 nếu muốn query gọn và tránh load toàn document quá lớn.

### Acceptance criteria

- Lấy đúng chunks cho `part/chapter/section/subsection`.
- Chunks được sort theo thứ tự nguồn.
- Scope không vượt document.
- Có unit test cho node có nhiều cấp con.

## 7. Phase 3: Enrichment Job và trạng thái riêng

### Mục tiêu

Tự động tạo artifact sau khi document `READY`, đồng thời cập nhật `enrichmentStatus` riêng để người dùng biết học liệu đã tạo xong hay chưa. Khi enrichment hoàn tất đầy đủ, document chuyển từ `READY` sang `FULL_USE`.

### Service đề xuất

`DocumentEnrichmentService`

Public methods:

- `enqueueDocumentEnrichment(Long documentId)`
- `enrichDocument(Long documentId, boolean forceRegenerate)`
- `enrichNode(Long documentNodeId, boolean forceRegenerate)`
- `retryFailedArtifacts(Long documentId)`

### Luồng tự động

Trong `DocumentProcessingService`, sau khi:

```java
document.setStatus(DocumentStatus.READY);
document.setEnrichmentStatus(DocumentEnrichmentStatus.QUEUED);
documentRepository.save(document);
```

gọi:

```java
documentEnrichmentService.enqueueDocumentEnrichment(documentId);
```

Job async:

1. Load document.
2. Nếu document không nằm trong nhóm `READY`/`FULL_USE`, skip.
3. Load nodes có `nodeType` thuộc:
   - `part`
   - `chapter`
   - `section`
   - `subsection`
4. Với mỗi node:
   - generate `SUMMARY`.
   - generate `REVIEW_QUESTION_SET`.
5. Nếu artifact đã tồn tại cùng `sourceHash/promptVersion/model` và `COMPLETED`, skip.
6. Nếu force regenerate, tạo artifact mới hoặc overwrite theo policy đã chọn.
7. Cập nhật `documents.enrichment_status`:
   - `QUEUED` khi vừa enqueue.
   - `RUNNING` khi bắt đầu xử lý.
   - `ENRICHED` nếu tất cả required artifacts `COMPLETED`, đồng thời set `DocumentStatus.FULL_USE`.
   - `PARTIAL_FAILED` nếu một phần artifact `FAILED`, document giữ `DocumentStatus.READY`.
   - `FAILED` nếu không tạo được artifact nào hoặc lỗi nghiêm trọng, document giữ `DocumentStatus.READY`.

Khi force regenerate:

- Set `enrichmentStatus = RUNNING`.
- Nếu artifacts hiện tại bị invalidated/overwrite, set document về `READY` trong lúc regenerate.
- Sau khi regenerate thành công toàn bộ, set document lại `FULL_USE`.

### Concurrency

Thêm executor riêng:

- `documentEnrichmentExecutor`
- default core/max: `1`
- queue: `50`

Không dùng chung executor parse để tránh upload/parse bị nghẽn bởi LLM enrichment.

### Config đề xuất

```yaml
application:
  rag:
    enrichment:
      enabled: true
      auto-run-after-ready: true
      prompt-version: "enrichment-v1"
      summary-enabled: true
      review-questions-enabled: true
      default-review-question-min-count: 15
      default-review-question-max-count: 20
      max-node-chunks: 120
      max-node-context-chars: 60000
      max-concurrency: 1
```

### Acceptance criteria

- Document upload xong và `READY` thì enrichment tự chạy.
- Enrichment hoàn tất toàn bộ thì document chuyển `FULL_USE`.
- Enrichment fail không đổi document từ `READY` sang `FAILED`.
- Artifact fail có `FAILED + errorMessage`.
- Có thể skip artifact đã có.
- `DocumentResponse` trả được cả `status` và `enrichmentStatus`.
- UI có thể hiển thị: `Sẵn sàng hỏi đáp`, `Đang tạo học liệu`, hoặc `Đủ học liệu`.

## 8. Phase 4: Prompt Builder và LLM Gateway

### Mục tiêu

Tách prompt riêng cho summary và question generation.

### Service đề xuất

`DocumentEnrichmentPromptBuilder`

Methods:

- `buildSummaryPrompt(Document document, DocumentNode node, List<DocumentChunk> chunks)`
- `buildReviewQuestionPrompt(Document document, DocumentNode node, List<DocumentChunk> chunks, int minCount, int maxCount)`

### Summary prompt requirements

- Trả lời bằng tiếng Việt.
- Chỉ dựa vào context.
- Summary là một đoạn văn ngắn.
- Có citation ngắn.
- Output JSON hợp lệ.
- Không thêm kiến thức ngoài tài liệu.

### Review question prompt requirements

- Trả lời bằng tiếng Việt.
- Sinh 15-20 câu.
- Phân bổ loại câu hỏi:
  - Multiple choice.
  - True/false.
  - Fill blank.
- Có đáp án.
- Có giải thích ngắn.
- Có độ khó nếu có thể.
- Có citation ngắn.
- Output JSON hợp lệ.

### JSON validation

Thêm service:

`DocumentEnrichmentArtifactValidationService`

Validate:

- JSON parse được.
- Summary không rỗng.
- Question count trong khoảng 15-20 nếu đủ context.
- Question type hợp lệ.
- MCQ có options và correctAnswer.
- TRUE_FALSE có correctAnswer boolean.
- FILL_BLANK có correctAnswer non-blank.
- Citation có `chunkId` hợp lệ trong scope.

### Acceptance criteria

- Prompt builder có unit tests.
- Validation bắt được JSON lỗi/thiếu field quan trọng.
- Artifact chỉ chuyển `COMPLETED` nếu validation pass.

## 9. Phase 5: Tích hợp Chatbot

### Mục tiêu

Chatbot dùng artifact precompute khi user hỏi tóm tắt hoặc tạo câu hỏi theo chương/phần/mục.

### Intent cần hỗ trợ

Thêm hoặc mở rộng intent:

- `FACTUAL_QA`
- `SECTION_SUMMARY`
- `REVIEW_QUESTION_GENERATION`

### Router

`RagIntentRouterService`

Rule-based trước:

- "tóm tắt", "tom tat", "khái quát", "ý chính" -> `SECTION_SUMMARY`
- "câu hỏi ôn tập", "tạo câu hỏi", "trắc nghiệm", "đúng sai", "điền khuyết" -> `REVIEW_QUESTION_GENERATION`
- còn lại -> `FACTUAL_QA`

Scope resolver:

- Parse `Phần I`, `Phần 1`.
- Parse `Chương II`, `Chương 2`.
- Parse `Mục 1.2`, `section`, `subsection`.
- Nếu không resolve được scope, fallback hỏi clarification hoặc dùng vector RAG hiện tại.

### Handler đề xuất

- `KnowledgeQaHandler`
- `SectionSummaryHandler`
- `ReviewQuestionHandler`

### SectionSummaryHandler

Luồng:

1. Resolve target node.
2. Tìm artifact `SUMMARY` mới nhất `COMPLETED`.
3. Nếu có, trả summary từ artifact.
4. Nếu chưa có, fallback:
   - generate on-demand từ chunks scope.
   - lưu artifact nếu validation pass.
5. Trả citation ngắn.

### ReviewQuestionHandler

Luồng:

1. Resolve target node.
2. Tìm artifact `REVIEW_QUESTION_SET` mới nhất `COMPLETED`.
3. Nếu user yêu cầu mặc định, trả artifact.
4. Nếu user yêu cầu tùy biến khác mặc định, dùng artifact + chunks gốc để generate on-demand.
5. Không lưu vào `QuestionBank` ở phase này.

### Acceptance criteria

- Hỏi "Tóm tắt Chương 2" dùng artifact.
- Hỏi "Tạo bộ câu hỏi ôn tập Chương 2" trả 15-20 câu có đáp án.
- Nếu artifact chưa sẵn sàng, fallback rõ ràng hoặc generate on-demand.
- Không phá luồng factual QA hiện có.

## 10. Phase 6: Admin/Debug/Retry API

### Mục tiêu

Cho giáo viên/admin xem trạng thái, retry, force regenerate artifact.

### Endpoint đề xuất

```http
GET /documents/{documentId}/artifacts
GET /documents/{documentId}/nodes/{nodeId}/artifacts
POST /documents/{documentId}/enrich
POST /documents/{documentId}/nodes/{nodeId}/enrich
POST /documents/{documentId}/artifacts/retry-failed
DELETE /documents/{documentId}/artifacts
```

### Request options

```json
{
  "artifactTypes": ["SUMMARY", "REVIEW_QUESTION_SET"],
  "forceRegenerate": false
}
```

### Response nên có

- artifact id
- document id
- node id
- node title/path/type
- artifact type
- status
- prompt version
- model
- source hash
- token count
- error message
- created/updated time

### Authorization

- `ADMIN`, `TEACHER`.
- Student không được gọi debug/regenerate.

### Acceptance criteria

- Xem được trạng thái toàn document.
- Retry được artifact failed.
- Force regenerate được một node hoặc toàn document.

## 11. Phase 7: Observability và Cost Control

### Log cần có

- Enrichment job start/end.
- Per-node artifact start/end.
- Duration.
- Token estimate.
- Status.
- Failure reason.

### Metrics nên có

- Số artifact `COMPLETED/FAILED/PENDING`.
- Số document theo `status`: `READY/FULL_USE/FAILED`.
- Số document theo `enrichmentStatus`: `QUEUED/RUNNING/ENRICHED/PARTIAL_FAILED/FAILED`.
- Thời gian enrichment trung bình mỗi document.
- Số LLM calls mỗi document.
- Token estimate mỗi document.

### Guardrails

- Max chunks per node.
- Max context chars per node.
- Timeout per LLM call.
- Retry/backoff giới hạn.
- Nếu node quá lớn, dùng map-reduce:
  - summarize batches;
  - reduce thành summary cuối;
  - generate question set từ reduced summary + selected chunks.

## 12. Phase 8: Test Plan

### Unit tests

- `DocumentNodeScopeServiceTest`
  - descendants đúng.
  - chunks đúng thứ tự.
  - source hash đổi khi content đổi.

- `DocumentEnrichmentPromptBuilderTest`
  - prompt có document title/node path/context/citation instruction.

- `DocumentEnrichmentArtifactValidationServiceTest`
  - validate summary.
  - validate MCQ.
  - validate TRUE_FALSE.
  - validate FILL_BLANK.
  - reject invalid citation chunk id.

- `DocumentEnrichmentServiceTest`
  - skip artifact đã `COMPLETED`.
  - force regenerate.
  - failed artifact không làm document mất `READY`.
  - document chuyển `FULL_USE` khi tất cả required artifacts hoàn tất.
  - cập nhật `enrichmentStatus` đúng khi success/partial failed/failed.

### Integration/service tests

- Document `READY` enqueue enrichment.
- Document `FULL_USE` vẫn có thể force regenerate.
- Enrich document tạo artifact cho `part/chapter/section/subsection`.
- Retry failed artifact.
- Chat summary dùng artifact.
- Chat review questions dùng artifact.

### Manual test cases

1. Upload PDF giáo trình có nhiều chương.
2. Kiểm tra `/documents/{id}/artifacts`.
3. Hỏi "Tóm tắt Chương 1".
4. Hỏi "Tạo 20 câu hỏi ôn tập Chương 1 gồm trắc nghiệm, đúng/sai, điền khuyết".
5. Force regenerate một node.
6. Retry failed artifact.

## 13. Rollout Plan

### Rollout bước 1

- Implement schema/entity/repository.
- Implement scope retrieval.
- Chưa bật auto enrichment.

### Rollout bước 2

- Implement summary artifact.
- Manual trigger qua admin endpoint.
- Test trên 1-2 tài liệu mẫu.

### Rollout bước 3

- Implement review question artifact.
- Manual trigger.
- Validate output và cost.

### Rollout bước 4

- Bật `auto-run-after-ready=true` ở dev.
- Theo dõi thời gian xử lý tài liệu và lỗi provider.

### Rollout bước 5

- Tích hợp chatbot dùng artifact.
- Fallback về retrieval hiện tại nếu artifact chưa sẵn sàng.

## 14. Rủi ro và cách giảm thiểu

| Rủi ro | Giảm thiểu |
| --- | --- |
| Enrichment tốn token khi document nhiều node | Config max node/chunks, map-reduce, có thể chỉ enrich node có content đủ dài |
| LLM trả JSON lỗi | Validation + retry + prompt strict JSON |
| Sinh câu hỏi lặp/không đều | Prompt yêu cầu phân bổ loại câu hỏi/độ khó, validation cơ bản |
| Artifact stale sau reindex | `sourceHash`; chỉ dùng artifact nếu hash match |
| Enrichment làm nghẽn upload | Executor riêng, queue riêng, concurrency thấp |
| Summary thiếu chi tiết | Summary là đoạn ngắn theo yêu cầu; câu hỏi vẫn dùng chunks gốc nếu cần |
| Citation không đúng | Validate citation chunk id thuộc scope |

## 15. Thứ tự triển khai khuyến nghị

1. Schema + entity + repository.
2. `DocumentNodeScopeService`.
3. `DocumentEnrichmentService` với `SUMMARY`.
4. Admin/debug endpoint cho artifact.
5. `REVIEW_QUESTION_SET`.
6. Tích hợp chatbot route summary/question generation.
7. Auto-run after `READY`.
8. Observability và cost guardrails.

## 16. Open Implementation Notes

- `document_nodes.content` hiện chưa lưu nội dung node; không cần dùng cho phase này.
- Source of truth cho context vẫn là `document_chunks`.
- Nên giữ artifacts theo document, không scope classroom trong phase này.
- Khi phát triển lưu vào `QuestionBank`, có thể map `REVIEW_QUESTION_SET.questions` sang DTO tạo câu hỏi sau.
- Khi prompt/model đổi, không tự regenerate. Admin endpoint force regenerate sẽ tạo artifact mới theo `promptVersion/model/sourceHash`.
