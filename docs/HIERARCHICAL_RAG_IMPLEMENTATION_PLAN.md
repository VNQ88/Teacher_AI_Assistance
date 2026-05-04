# Hierarchical RAG Implementation Plan

## Mục Tiêu

Triển khai Hierarchical RAG dựa trên cây cấu trúc tài liệu đã parse được:

```text
document -> part -> chapter -> section -> subsection -> child chunk
```

Mục tiêu runtime:

- Vector search vẫn chạy trên `document_chunks`.
- Parent-child grouping chạy bằng `document_nodes`.
- Citation dùng `page_from/page_to`, fallback sang `section_path` và `char_start/char_end`.
- `*.md`, `*.hierarchy.json`, `*.chunks.jsonl` chỉ là artifact để debug, audit, reindex, không phải nguồn dữ liệu chính khi hỏi đáp.

## Những Phần Cần Sửa Trước Khi Triển Khai

### 1. Đồng bộ schema DB với code hiện tại

Schema dump hiện tại có `document_nodes` và các cột hierarchical trong `document_chunks`, nhưng entity/code chưa persist đầy đủ vào các cột này.

Cần kiểm tra và sửa:

- `documents` cần có các object key mới:
  - `hierarchy_object_key`
  - `chunks_object_key`
- `document_nodes` nên có thêm `node_key` để lưu id ổn định từ parser, ví dụ `n1`, `n2`.
- `document_chunks.node_id` và `document_chunks.parent_node_id` phải được set bằng FK thật tới `document_nodes.id`, không chỉ lưu `nodeId` trong `metadata_jsonb`.
- `document_chunks.section_path`, `page_from`, `page_to`, `source_order` phải được set trực tiếp để query nhanh.
- Dọn lại các constraint lạ trong `documents` nếu còn xuất hiện dạng `unique ()`.

Migration đề xuất:

```sql
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS hierarchy_object_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS chunks_object_key VARCHAR(500);

ALTER TABLE document_nodes
    ADD COLUMN IF NOT EXISTS node_key VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_nodes_document_node_key
    ON document_nodes(document_id, node_key);

CREATE INDEX IF NOT EXISTS idx_document_nodes_doc_parent_order
    ON document_nodes(document_id, parent_id, order_index);
```

### 2. Hoàn thiện entity/repository

Cần thêm hoặc cập nhật:

- `DocumentNode` entity.
- `DocumentNodeRepository`.
- Quan hệ optional từ `DocumentChunk` tới `DocumentNode`.
- Repository methods:
  - delete nodes by document id
  - find tree by document id ordered by `order_index`
  - find parent nodes by ids
  - find chunks by parent node ids

### 3. Persist full hierarchy tree vào DB

Hiện artifact đã có `hierarchy.json` đầy đủ. Bước còn thiếu là persist tree đó vào `document_nodes`.

Cần làm:

- Insert toàn bộ node theo pre-order.
- Lưu mapping `node_key -> document_nodes.id`.
- Set `parent_id` bằng DB id thật.
- Lưu:
  - `node_type`
  - `level`
  - `title`
  - `section_path`
  - `order_index`
  - `page_from/page_to`
  - `content`
  - `metadata_jsonb`

### 4. Persist chunks với FK tới node

Khi ingest chunks:

- Lấy `node_id` từ mapping node.
- Lấy `parent_node_id` từ mapping parent node.
- Set các cột trực tiếp:
  - `node_id`
  - `parent_node_id`
  - `section_path`
  - `page_from`
  - `page_to`
  - `source_order`
- Vẫn giữ metadata JSONB để debug và mở rộng.

### 5. Tiếp tục cải thiện parser chất lượng

Artifact hiện đã đủ dùng, nhưng vẫn cần refinement:

- Giảm false heading trong tài liệu Pháp luật, nhất là các câu nội dung bị nhận thành heading.
- Tách thêm heading dính body trong tài liệu Triết học.
- Đảm bảo `REVIEW_QUESTIONS` không tạo parent sai nếu câu hỏi nằm trong phần ôn tập.
- Thêm validator fail/warn nếu:
  - heading quá dài
  - breadcrumb bắt đầu bằng một câu body
  - node không có content và không có child
  - page range null với PDF

## Artifact Contract

### Markdown

`*.md` là bản normalized để debug và human review.

Yêu cầu:

- Có heading Markdown ổn định.
- Có page marker nếu lấy được từ PDF:

```md
<!-- page: 12 -->
```

### hierarchy.json

`*.hierarchy.json` là snapshot full tree.

Yêu cầu tối thiểu:

- `documentId`
- `title`
- `sourceObjectKey`
- `markdownObjectKey`
- `nodeCount`
- `chunkCount`
- `root`
- `nodes`

Mỗi node cần có:

- `nodeId`: key ổn định từ parser, ví dụ `n12`
- `parentNodeId`
- `nodeType`
- `title`
- `breadcrumb`
- `pageFrom`
- `pageTo`
- `charStart`
- `charEnd`
- `contentCharCount`
- `children`

### chunks.jsonl

`*.chunks.jsonl` là snapshot child retrieval units.

Mỗi dòng cần có:

- `documentId`
- `chunkIndex`
- `chunkType`: `TEXT`, `SUMMARY`, `REVIEW_QUESTIONS`
- `nodeType`
- `nodeId`
- `parentNodeId`
- `sectionHeader`
- `breadcrumb`
- `pageFrom`
- `pageTo`
- `charStart`
- `charEnd`
- `content`

## Phase 0: Schema Và Model Alignment

Mục tiêu: DB, entity, repository phản ánh đúng mô hình Hierarchical RAG.

Việc cần làm:

1. Tạo migration bổ sung `documents.hierarchy_object_key`, `documents.chunks_object_key` nếu DB thật chưa có.
2. Bổ sung `document_nodes.node_key`.
3. Bổ sung unique index `(document_id, node_key)`.
4. Tạo `DocumentNode` entity.
5. Tạo `DocumentNodeRepository`.
6. Cập nhật `DocumentChunk` entity:
   - `DocumentNode node`
   - `DocumentNode parentNode`
   - `sectionPath`
   - `pageFrom`
   - `pageTo`
   - `sourceOrder`
7. Cập nhật `DocumentResponse` để trả object keys mới.
8. Cập nhật delete document để xóa artifacts và để cascade xóa nodes/chunks.

Acceptance criteria:

- App boot thành công.
- Migration chạy sạch trên DB dev.
- Có thể query `document_nodes` theo `document_id`.
- `document_chunks.node_id` và `parent_node_id` tồn tại trong entity.

## Phase 1: Artifact Generation Stabilization

Mục tiêu: parser sinh artifact đủ ổn định để persist DB.

Việc cần làm:

1. Giữ output:
   - normalized `.md`
   - `.hierarchy.json`
   - `.chunks.jsonl`
2. Tạo validator service cho artifact:
   - JSON parse được.
   - JSONL parse được từng dòng.
   - `placeholderCount = 0`.
   - mọi chunk có breadcrumb.
   - mọi chunk có parent node.
   - mọi PDF chunk có `pageFrom/pageTo`.
   - chunk length trong khoảng hợp lý.
3. Thêm warning report vào log khi:
   - heading dài hơn ngưỡng
   - `sectionHeader` có dấu hiệu dính body
   - breadcrumb nghi ngờ bắt đầu bằng câu body
4. Viết regression test cho 3 tài liệu mẫu:
   - Pháp luật đại cương
   - Lịch sử Đảng
   - Triết học Mác-Lênin

Acceptance criteria:

- 3 tài liệu mẫu sinh đủ 3 artifact.
- `hierarchy.json.placeholderCount = 0`.
- `chunks.jsonl.emptyBreadcrumb = 0`.
- `pageFilled = chunkCount` với PDF text-based.

## Phase 2: Persist Document Nodes

Mục tiêu: `document_nodes` trở thành nguồn cây tài liệu chính trong runtime.

Việc cần làm:

1. Tạo `DocumentHierarchyPersistenceService`.
2. Input service:
   - `Document`
   - `HierarchicalMarkdownDocument`
3. Insert node root và children theo pre-order.
4. Tính `level` từ depth thật trong tree.
5. Tính `order_index` theo thứ tự xuất hiện trong tài liệu.
6. Set `node_key` bằng parser node id.
7. Set `parent_id` bằng DB id thật.
8. Set `section_path` bằng breadcrumb join, ví dụ:

```text
Chương 1 > 1.1 > 1.1.1
```

9. Set `content`:
   - Có thể lưu content node nếu cần parent expansion.
   - Nếu không muốn duplicate quá nhiều text, lưu null và dùng children/chunks để expand.
10. Set `metadata_jsonb`:
   - `breadcrumb`
   - `charStart`
   - `charEnd`
   - `contentCharCount`
   - `sourceNodeKey`

Acceptance criteria:

- Sau ingest, `document_nodes` có đủ node bằng `hierarchy.nodeCount`.
- Không còn node orphan ngoài root.
- `node_key` unique trong một document.
- Query tree theo `document_id` reconstruct được đúng structure.

## Phase 3: Persist Chunks Và Embeddings

Mục tiêu: `document_chunks` lưu child retrieval units có FK tới node.

Việc cần làm:

1. Cập nhật `DocumentChunkIngestionService` để nhận `HierarchicalMarkdownDocument` hoặc list chunks + node mapping.
2. Với mỗi chunk:
   - resolve `node_id` từ `chunk.nodeId`
   - resolve `parent_node_id` từ `chunk.parentNodeId`
   - set `chunk_index`
   - set `source_order`
   - set `chunk_type`
   - set `section_path`
   - set `page_from/page_to`
   - set `metadata_jsonb`
3. Embed content như hiện tại.
4. Update embedding literal.
5. Đảm bảo transaction:
   - nếu node persist fail thì không persist chunk
   - nếu embedding fail thì document status `FAILED`
6. Trước reindex:
   - delete message source links
   - delete chunks
   - delete nodes
   - rebuild from artifacts/source

Acceptance criteria:

- `document_chunks.node_id` không null với chunks hierarchical.
- `document_chunks.parent_node_id` không null trừ root-level fallback đặc biệt.
- Vector retrieval vẫn chạy.
- Join chunk -> node -> parent node hoạt động.

## Phase 4: Parent-Aware Retrieval

Mục tiêu: retrieval không chỉ lấy top child chunks rời rạc mà group theo parent semantic unit.

Việc cần làm:

1. Vector search lấy candidate chunks theo subject/classroom/document scope.
2. Candidate count nên lớn hơn topK, ví dụ:
   - `candidateTopK = 24`
   - `parentTopK = 4`
   - `childPerParent = 2-4`
3. Group candidates theo `parent_node_id`.
4. Tính score parent:
   - max child vector score
   - average score của top child chunks
   - lexical overlap giữa query và `section_path`
   - boost nếu query chứa số chương/mục khớp breadcrumb
   - boost/penalty theo `chunk_type`
5. Chọn top parent groups.
6. Trong mỗi parent group:
   - chọn top child chunks
   - sắp xếp theo `source_order`
   - deduplicate overlap
7. Optional expansion:
   - lấy sibling chunks liền trước/sau nếu câu trả lời thiếu ngữ cảnh
   - lấy parent summary nếu query yêu cầu tổng quan

Acceptance criteria:

- Retrieval response có parent groups.
- Không trả chunks lộn xộn từ nhiều section khi câu hỏi rõ section.
- Câu hỏi tổng quan ưu tiên parent/summary tốt hơn.

## Phase 5: Chunk Type Policy

Mục tiêu: dùng `TEXT`, `SUMMARY`, `REVIEW_QUESTIONS` đúng ngữ cảnh.

Policy:

- Query hỏi kiến thức/fact:
  - ưu tiên `TEXT`
  - dùng `SUMMARY` chỉ làm expansion
  - bỏ qua `REVIEW_QUESTIONS`
- Query hỏi “tóm tắt chương/mục”:
  - ưu tiên `SUMMARY`
  - bổ sung `TEXT` nếu summary thiếu
- Query hỏi “câu hỏi ôn tập”, “luyện tập”, “đề cương”:
  - ưu tiên `REVIEW_QUESTIONS`
  - bổ sung `TEXT` để giải thích nếu cần

Việc cần làm:

1. Tạo intent classifier nhẹ bằng rule trong `VectorRetrievalService`.
2. Filter hoặc boost chunk type theo intent.
3. Log chunk type distribution trong retrieval diagnostics.

Acceptance criteria:

- Câu hỏi ôn tập trả review chunks.
- Câu hỏi định nghĩa không bị nhiễu bởi review questions.
- Câu hỏi tóm tắt chương dùng summary khi có.

## Phase 6: Prompt Context Assembly

Mục tiêu: prompt có context rõ nguồn, rõ path, rõ page.

Context format:

```text
[Source 1]
Document: <document title>
Path: <section_path>
Pages: <page_from>-<page_to>
Chunk type: <chunk_type>
Content:
<chunk content>
```

Việc cần làm chi tiết:

1. Chuẩn hóa input context từ retrieval:
   - Nhận `List<DocumentChunk>` đã được parent-aware retrieval chọn.
   - Giữ nguyên thứ tự source trả về từ retrieval để source index ổn định.
   - Không sort lại theo score trong prompt builder.
   - Deduplicate theo `chunk.id` trước khi render nếu cùng chunk xuất hiện lặp.
2. Cập nhật `RagPromptBuilderService` để render từng source theo block:
   - `[Source n]`
   - `Document`
   - `Path`
   - `Pages`
   - `Chunk type`
   - `Content`
3. Mapping metadata khi render:
   - `Document` lấy từ `chunk.document.title`.
   - `Path` lấy từ `chunk.sectionPath`.
   - `Pages` lấy từ `chunk.pageFrom/pageTo`, nếu không có thì `N/A`.
   - `Chunk type` lấy từ `chunk.chunkType`, fallback `TEXT`.
4. Điều chỉnh prompt policy:
   - Chỉ trả lời dựa trên context được cung cấp.
   - Nếu context thiếu thì nói không đủ dữ liệu, không suy diễn.
   - Nếu context mâu thuẫn thì nêu mâu thuẫn và hỏi lại một câu.
   - Khi dùng evidence, cite bằng source index/page/path, ví dụ `[Source 1, pages 12-13]`.
   - Không cite raw chunk id như `[Chunk 123]`.
5. Tối ưu prompt size:
   - Giới hạn số chunks theo `topK` hiện tại.
   - Không đưa quá nhiều chunks cùng parent nếu nội dung trùng.
   - Cắt snippet trong prompt chỉ khi token budget vượt ngưỡng; mặc định giữ full selected chunk.
6. Bổ sung tests:
   - Prompt có `Source n`.
   - Prompt có document title, section path, pages, chunk type.
   - Prompt không còn raw `[Chunk id]`.
   - Prompt dùng newline thật để LLM đọc block rõ ràng.

Acceptance criteria:

- Prompt dễ trace từ câu trả lời về chunk.
- Answer có thể cite `[Source 1, pages 12-13]`.
- Không mất breadcrumb khi LLM trả lời.

## Phase 7: Citation Và Source Tracking

Mục tiêu: source được lưu và trả về API nhất quán.

Việc cần làm chi tiết:

1. Đảm bảo selected chunks được persist:
   - `RagChatService` set `assistantMessage.sourceChunks = selectedChunks`.
   - Hibernate persist vào `message_source_chunks`.
   - Message history load lại vẫn có thể truy ra chunks đã dùng.
2. Giữ field API cũ:
   - `sources`: danh sách distinct document titles để không phá client hiện tại.
3. Bổ sung DTO source chi tiết:
   - Tạo `SourceChunkResponse`.
   - Thêm `ChatMessageResponse.sourceDetails`.
4. `sourceDetails` cần trả:
   - `sourceIndex`
   - `chunkId`
   - `documentId`
   - `documentTitle`
   - `sectionPath`
   - `pageFrom`
   - `pageTo`
   - `chunkType`
   - `charStart`
   - `charEnd`
   - `snippet`
5. Citation fallback:
   - Ưu tiên `pageFrom/pageTo`.
   - Nếu không có page, dùng `sectionPath`.
   - Nếu không có section path, dùng `charStart/charEnd` từ `metadataJsonb`.
   - Nếu vẫn thiếu, chỉ trả `documentTitle + snippet`.
6. Cập nhật confidence/citation parsing:
   - Chấp nhận citation mới dạng `[Source 1]`, `[Source 1, pages 12-13]`.
   - Giữ tương thích citation cũ `[Chunk id]` nếu còn answer cũ trong history.
7. Kiểm tra quyền truy cập nguồn:
   - Khi trả history theo session owned, source chunks chỉ đi qua session đã authorize.
   - Với endpoint source/debug sau này, phải filter theo user/subject/classroom.
8. Bổ sung tests:
   - `sendMessage` trả `sourceDetails` đầy đủ.
   - `sourceIndex` ổn định theo thứ tự selected chunks.
   - `snippet` được cắt ngắn hợp lý.
   - Confidence tăng khi answer cite `[Source n]`.
   - History response vẫn trả source details từ `message_source_chunks`.

Acceptance criteria:

- Message history biết câu trả lời dựa trên chunks nào.
- UI/API có thể hiển thị nguồn.
- Không expose tài liệu ngoài subject/classroom scope.

## Phase 8: API Debug Và Admin Tools

Mục tiêu: dễ kiểm tra retrieval/hierarchy khi tuning.

Endpoints đề xuất:

- `GET /api/documents/{id}/hierarchy`
- `GET /api/documents/{id}/nodes`
- `GET /api/documents/{id}/chunks?type=TEXT|SUMMARY|REVIEW_QUESTIONS`
- `POST /api/rag/debug-retrieve`

Debug retrieve response:

- query
- detected intent
- candidate chunks
- parent groups
- selected chunks
- scores
- rejected chunk types
- final prompt context preview

Acceptance criteria:

- Có thể debug vì sao một answer chọn nguồn X.
- Có thể xem tree từ DB, không cần đọc JSON file.
- Có thể so sánh DB tree với `hierarchy.json` khi cần.

## Phase 9: Evaluation Và Tuning

Mục tiêu: đo chất lượng trước production.

Tạo bộ câu hỏi cho từng tài liệu:

- Definition questions.
- Section-specific questions.
- Chapter summary questions.
- Cross-section comparison questions.
- Review-question requests.
- Questions intentionally outside context.

Metrics:

- Retrieval hit rate.
- Parent grouping correctness.
- Citation correctness.
- Groundedness.
- Refusal correctness khi thiếu context.
- Latency.
- Token usage.

Acceptance criteria ban đầu:

- Retrieval đúng tài liệu và đúng section với câu hỏi rõ ràng.
- Không dùng `REVIEW_QUESTIONS` cho factual QA thông thường.
- Citation page/path đúng với selected source.
- Không hallucinate khi thiếu context.

## Phase 10: Cleanup Và Production Hardening

Việc cần làm:

1. Reindex flow:
   - delete chunks
   - delete nodes
   - rebuild artifacts
   - persist nodes
   - persist chunks
   - embed
2. Failure handling:
   - status `FAILED`
   - store processing error
   - keep original file
   - optionally keep failed artifacts for debugging
3. Observability:
   - log node count
   - chunk count
   - page coverage
   - chunk type distribution
   - embedding time
4. Index tuning:
   - keep HNSW for embedding
   - use btree indexes for `parent_node_id`, `node_id`, `subject_id`
   - use GIN JSONB only for flexible debug/filter fields

Acceptance criteria:

- Reindex deterministic.
- Delete document cleans chunks/nodes/artifacts.
- Processing failures are recoverable.
- Retrieval latency acceptable with expected dataset size.

## Suggested Implementation Order

1. Schema/entity/repository alignment.
2. Persist `document_nodes`.
3. Persist chunks with FK to nodes.
4. Enable embeddings on hierarchical chunks.
5. Implement parent-aware retrieval.
6. Implement chunk type policy.
7. Update prompt and citation format.
8. Add debug endpoints.
9. Build evaluation set.
10. Tune parser/retrieval based on evaluation.

## Open Questions

- Có cần lưu full node `content` trong `document_nodes` hay chỉ lưu metadata và dùng chunks để expand?
- Có cần LLM-generated parent summaries riêng không, hay dùng summary có sẵn từ giáo trình?
- Có cần hybrid search BM25 + vector không?
- Có cần reranker model riêng sau vector retrieval không?
- Có cần dùng `ltree` cho `section_path` nếu query cây nhiều hơn không?
