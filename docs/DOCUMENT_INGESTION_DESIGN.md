# Document Ingestion Design (Docling + MinIO + RAG)

## 1) Muc tieu
- Xay dung luong day tai lieu (PDF/DOCX/TXT) vao he thong de phuc vu RAG.
- Trich xuat noi dung bang Docling (qua `docling-serve-client`) va luu ket qua markdown.
- Luu dong thoi file goc + file markdown len MinIO theo tung `subject`.
- Cap nhat trang thai xu ly theo `DocumentStatus` xuyen suot.
- Trang thai `READY` chi dat khi da chunk + embedding xong vao vector DB (pgvector).

## 2) Pham vi va quy uoc da chot
- Dinh dang ho tro: `PDF`, `DOCX`, `TXT`.
- Gioi han kich thuoc:
  - `DOCX`, `TXT`: `< 100 KB`.
  - `PDF`: theo cau hinh upload chung cua he thong.
- PDF bat buoc split thanh tung phan `10 trang` truoc khi goi Docling.
- Khi parse Docling: tat cac tinh nang lien quan den OCR va xu ly anh.
- Sau parse: loai bo noi dung anh du thua trong markdown (vi du `Image:base64,...`).
- Migration: su dung `JPA ddl-auto` + script SQL versioned thu cong (khong dung Liquibase cho luong moi).

## 3) Hien trang code lien quan
- `DocumentStatus`: `UPLOADED`, `PARSING`, `CHUNKING`, `EMBEDDING`, `READY`, `FAILED`.
  - File: `src/main/java/com/example/teacherassistantai/common/enumerate/DocumentStatus.java`
- Entity tai lieu:
  - `Document`: metadata file, status, processingError.
  - `DocumentChunk`: chunk text + `embedding` (pgvector) + `subject_id` denormalized.
  - Files:
    - `src/main/java/com/example/teacherassistantai/entity/Document.java`
    - `src/main/java/com/example/teacherassistantai/entity/DocumentChunk.java`
- MinIO integration san co:
  - `src/main/java/com/example/teacherassistantai/integration/minio/MinioChannel.java`
  - `src/main/java/com/example/teacherassistantai/integration/minio/MinioConfig.java`
  - `src/main/java/com/example/teacherassistantai/integration/minio/MinioProps.java`
- Da them dependency Docling client:
  - `build.gradle.kts`: `implementation("ai.docling:docling-serve-client:0.5.0")`

## 4) Kien truc tong quan de xuat

```text
Client upload
   -> DocumentController (POST /documents)
      -> Validate request + file constraints
      -> Upload original to MinIO (subject/{id}/original/...)
      -> Save Document(status=UPLOADED)
      -> Trigger async processing

DocumentProcessingService (@Async)
   -> set PARSING
   -> Download original (neu can)
   -> Branch by fileType:
      - PDF: split 10 pages/chunk -> parse moi chunk bang Docling
      - DOCX/TXT: parse truc tiep bang Docling
   -> Merge markdown + sanitize (remove Image:base64,...)
   -> Upload markdown to MinIO (subject/{id}/md/...)
   -> set CHUNKING
   -> Chunk text -> save DocumentChunk
   -> set EMBEDDING
   -> Generate embeddings -> persist vector vao document_chunks.embedding
   -> set READY

Neu loi o bat ky buoc:
   -> set FAILED + processingError
```

## 5) Data model de xuat (bo sung)
Ngoai cac truong hien co trong `Document`, de xuat bo sung de theo doi day du 2 artifact:
- `originalObjectKey`
- `markdownObjectKey`
- `parseStartedAt`, `parseCompletedAt` (optional)
- `retryCount` (optional)
- `idempotencyKey` (optional)

> URL co the duoc sinh dong qua MinIO pre-signed GET (`MinioChannel.presignedGetUrl`), khong can luu `fileUrl` trong DB.

## 6) API de xuat
### 6.1 Upload document
- `POST /documents`
- Content: `multipart/form-data`
- Input:
  - `file`
  - `subjectId` (required)
  - `classroomId` (optional)
  - `title`, `description` (optional)
- Output (`ResponseData<DocumentResponse>`):
  - `id`, `status` (ban dau `UPLOADED`), metadata file

### 6.2 Get processing status
- `GET /documents/{id}`
- Output (`ResponseData<DocumentResponse>`): status, error, links file goc/md (neu co)

### 6.3 List by subject
- `GET /documents?subjectId=&status=&pageNo=&pageSize=`
- Tra ve `PageResponse` theo convention hien tai.

## 7) Pipeline chi tiet theo trang thai
1. `UPLOADED`
   - Dieu kien vao: file hop le, da luu original len MinIO, da tao record `Document`.
2. `PARSING`
   - Doc file + goi Docling parse.
   - PDF: split 10 trang/lot va parse tung lot.
   - Tat OCR/image processing khi goi Docling API.
3. `CHUNKING`
   - Tao chunks tu markdown da clean va luu `DocumentChunk`.
4. `EMBEDDING`
   - Sinh embedding cho tung chunk, persist vao vector column.
5. `READY`
   - Chi set khi tat ca chunks da co embedding va san sang phuc vu RAG.
6. `FAILED`
   - Set khi gap loi; ghi `processingError` de truy vet.

## 8) Tich hop Docling: 2 huong va khuyen nghi

### A. Gui file goc/binary truc tiep
- Cac buoc:
  - Backend cat PDF 10 trang thanh in-memory/temp files.
  - Gui binary tung chunk den Docling.
- Uu diem:
  - Hieu qua hon cho PDF split (it I/O voi MinIO).
  - Khong can tao presigned URL cho tung chunk.
  - Retry theo chunk de kiem soat tai/ngat ket noi tot hon.
- Nhuoc diem:
  - Backend ton RAM/CPU hon khi xu ly file lon.

### B. Gui URL cho Docling tu pull
- Cac buoc:
  - Upload chunk/original len MinIO.
  - Docling GET qua URL.
- Uu diem:
  - Giam payload giua backend -> Docling.
- Nhuoc diem:
  - Tang I/O object storage.
  - Quan ly URL (presigned/public) phuc tap hon, nhat la cho nhieu PDF chunks.

### Ket luan hien tai
- Luong mac dinh duoc chot: **PDF split -> gui binary tung chunk vao Docling**.
- URL mode de du phong khi ha tang khong cho phep trao doi binary hieu qua.

## 9) Docling co can URL public/presigned GET khong?
- **Khong bat buoc** neu Docling truy cap duoc MinIO trong cung network noi bo (private).
- **Can** URL public/presigned GET neu Docling o network khac hoac khong doc duoc endpoint noi bo.
- Khuyen nghi:
  - Uu tien private/internal networking.
  - Chi dung presigned GET khi that su can thiet.

## 10) Xu ly markdown sau parse (sanitization)
- Loai bo cac doan lien quan image/base64, vi du:
  - `Image:base64,...`
  - Data URI anh nhung trong markdown/html.
- Chuan hoa khoang trang, heading va separator de chunking on dinh.
- Ghi log do dai markdown truoc/sau clean de de debug.

## 11) Error handling, retry, idempotency
- Bat ky exception trong async pipeline -> `FAILED` + `processingError`.
- Retry co kiem soat:
  - Retry theo chunk voi PDF.
  - Gioi han so lan retry (vd 2-3 lan/chunk).
- Idempotency:
  - Tranh tao trung `DocumentChunk` neu job chay lai.
  - Co the xoa chunks cu theo `document_id` truoc khi reprocess.

## 12) Cau hinh de xuat
Trong `src/main/resources/application-dev.yml`, bo sung nhom cau hinh cho docling va ingest:
- `application.docling.base-url`
- `application.docling.timeout-seconds`
- `application.docling.parse.disable-ocr=true`
- `application.docling.parse.disable-image-processing=true`
- `application.document.max-docx-bytes=102400`
- `application.document.max-txt-bytes=102400`
- `application.document.pdf-split-pages=10`
- `application.document.parse-concurrency=2|4|6`

## 13) Migration va DB strategy (khong Liquibase)
- Huong da chot: `ddl-auto` + SQL versioned thu cong.
- De xuat quy trinh:
  1. Script SQL theo version (vd `V20260325__document_ingestion_metadata.sql`) trong `src/main/resources/db/changes/`.
  2. Chay script qua psql/CI migration job truoc khi deploy.
  3. `ddl-auto` dung cho local/dev convenience; production uu tien script co kiem soat.
- Ghi chu: hien project van co config/dependency Liquibase; can don dep khi trien khai thiet ke nay de tranh hieu nham.

## 14) Checklist kiem thu
- Upload validation:
  - Reject DOCX/TXT >= 100KB.
  - Accept PDF va xu ly split dung 10 trang/chunk.
- Parsing:
  - Docling duoc goi voi OCR/image processing da tat.
  - Markdown output khong con `Image:base64`.
- Storage:
  - Original luu dung folder `subject/{id}/original/`.
  - Markdown luu dung folder `subject/{id}/md/`.
- Status transitions:
  - Day du `UPLOADED -> PARSING -> CHUNKING -> EMBEDDING -> READY`.
  - Loi o moi phase deu ve `FAILED` + co `processingError`.
- RAG readiness:
  - `READY` chi khi tat ca chunks da co embedding trong vector DB.

## 15) Rollout de xuat
1. Implement integration + service async + API upload/status.
2. Them script SQL versioned cho truong metadata moi.
3. Viet test cho:
   - validation size/type,
   - status transition,
   - PDF split logic,
   - markdown sanitizer.
4. Chay thu tren moi truong dev voi PDF lon va DOCX/TXT nho.
5. Theo doi log/metrics parse latency, chunk count, failure rate.

## 16) Quyet dinh da chot den hien tai
- `READY` = da embedding xong, san sang RAG.
- PDF phai split 10 trang truoc parse.
- Luong PDF toi uu: gui binary chunk vao Docling.
- Ho tro URL mode khi can, va co the can presigned/public GET neu khac network.
- Dung `docling-serve-client:0.5.0` de goi Docling.
- Migration theo JPA + SQL versioned thu cong (khong dung Liquibase cho luong moi).
