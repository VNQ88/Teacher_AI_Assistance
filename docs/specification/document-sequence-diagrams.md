# Đặc tả các use case tài liệu

## 1. Upload tài liệu

| Mục | Nội dung |
| --- | --- |
| Usecase | Upload tài liệu |
| Tác nhân chính | Admin / Giáo viên |
| Mô tả | Admin hoặc giáo viên upload file tài liệu cho một môn học. Sau khi lưu thành công, hệ thống tự bắt đầu xử lý tài liệu ở nền. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền `ADMIN` hoặc `TEACHER`. |
| Luồng sự kiện chính | 1. Admin/Giáo viên chọn file và môn học<br>2. Giao diện gửi yêu cầu `POST /api/documents`<br>3. Hệ thống kiểm tra quyền Admin/Giáo viên<br>4. Hệ thống kiểm tra file, môn học và người dùng hiện tại<br>5a. File hợp lệ: hệ thống lưu file gốc vào MinIO Storage<br>6. Hệ thống lưu tài liệu ở trạng thái đã upload<br>7. Sau khi lưu thành công, hệ thống bắt đầu xử lý nền<br>8. Hệ thống thông báo tạo tài liệu thành công<br>9. Giao diện hiển thị kết quả upload tài liệu |
| Luồng sự kiện thay thế | TH1. File lỗi, môn học không tồn tại hoặc lưu file thất bại<br>- 5b. Hệ thống báo lỗi upload<br>- 9. Giao diện hiển thị kết quả upload tài liệu |
| Hậu điều kiện | - Nếu upload thành công, file gốc được lưu trong MinIO Storage, thông tin tài liệu được lưu trong cơ sở dữ liệu và tác vụ xử lý nền được khởi chạy. |

## 2. Xử lý tài liệu nền

| Mục | Nội dung |
| --- | --- |
| Usecase | Xử lý tài liệu nền |
| Tác nhân chính | Tác vụ nền |
| Mô tả | Hệ thống tự xử lý tài liệu sau khi upload hoặc sau khi người dùng yêu cầu xử lý lại. Kết quả xử lý được dùng cho tìm kiếm, hỏi đáp và tạo học liệu. |
| Tiền điều kiện | Tài liệu đã được upload hoặc được đưa về trạng thái chờ xử lý lại. |
| Luồng sự kiện chính | 1. Tác vụ nền nhận mã tài liệu sau upload hoặc xử lý lại<br>2. Hệ thống cập nhật trạng thái đang đọc file<br>3. Hệ thống tải file gốc từ MinIO Storage<br>4. Hệ thống chuyển nội dung sang dạng văn bản<br>5. Hệ thống tạo file kết quả xử lý gồm văn bản, mục và đoạn nội dung<br>6. Hệ thống lưu file kết quả xử lý vào MinIO Storage<br>7. Hệ thống lưu mục tài liệu và khung đoạn nội dung<br>8. Hệ thống tạo vector tìm kiếm theo từng nhóm<br>9. Hệ thống lưu vector tìm kiếm và chuyển sang bước tạo học liệu<br>10. Hệ thống đưa yêu cầu tạo tóm tắt vào hàng đợi<br>11a. Hệ thống tạo tóm tắt theo từng mục<br>12. Hệ thống lưu học liệu và đánh dấu tài liệu đã sẵn sàng |
| Luồng sự kiện thay thế | TH1. Một phần học liệu bị lỗi<br>- 11b. Hệ thống lưu lỗi học liệu và đánh dấu lỗi một phần<br><br>TH2. Lỗi trước khi tạo học liệu<br>- 11c. Hệ thống lưu lỗi xử lý và đánh dấu thất bại |
| Hậu điều kiện | - Nếu xử lý thành công, tài liệu có dữ liệu văn bản, mục, đoạn nội dung, vector tìm kiếm và học liệu sẵn sàng sử dụng.<br>- Nếu có lỗi, hệ thống lưu trạng thái lỗi để người dùng có thể xem hoặc yêu cầu xử lý lại. |

## 3. Xem tài liệu

| Mục | Nội dung |
| --- | --- |
| Usecase | Xem tài liệu |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xem danh sách tài liệu hoặc xem thông tin chi tiết của một tài liệu cụ thể. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng mở màn hình tài liệu<br>2. Hệ thống kiểm tra đăng nhập<br>3a. Xem danh sách: giao diện gửi yêu cầu `GET /api/documents`<br>4a. Hệ thống lọc theo môn học, trạng thái và phân trang<br>5a. Hệ thống trả danh sách tài liệu<br>6. Giao diện hiển thị kết quả xem tài liệu |
| Luồng sự kiện thay thế | TH1. Xem chi tiết và tìm thấy tài liệu<br>- 3b. Giao diện gửi yêu cầu `GET /api/documents/{documentId}`<br>- 4b. Hệ thống tìm tài liệu theo mã tài liệu<br>- 5b. Hệ thống trả chi tiết tài liệu<br>- 6. Giao diện hiển thị kết quả xem tài liệu<br><br>TH2. Xem chi tiết nhưng không tìm thấy tài liệu<br>- 3b. Giao diện gửi yêu cầu `GET /api/documents/{documentId}`<br>- 4b. Hệ thống tìm tài liệu theo mã tài liệu<br>- 5c. Hệ thống báo không tìm thấy tài liệu<br>- 6. Giao diện hiển thị kết quả xem tài liệu |
| Hậu điều kiện | - Người dùng nhận được danh sách tài liệu hoặc thông tin chi tiết tài liệu cần xem. |

## 4. Xử lý lại tài liệu

| Mục | Nội dung |
| --- | --- |
| Usecase | Xử lý lại tài liệu |
| Tác nhân chính | Admin / Giáo viên |
| Mô tả | Admin hoặc giáo viên yêu cầu hệ thống xử lý lại một tài liệu, thường dùng khi tài liệu xử lý lỗi hoặc cần tạo lại dữ liệu xử lý. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền `ADMIN` hoặc `TEACHER`. |
| Luồng sự kiện chính | 1. Admin/Giáo viên chọn xử lý lại tài liệu<br>2. Giao diện gửi yêu cầu `POST /api/documents/{documentId}/reprocess`<br>3. Hệ thống kiểm tra quyền Admin/Giáo viên<br>4. Hệ thống tìm tài liệu và người dùng hiện tại<br>5a. Có quyền xử lý lại: hệ thống đưa tài liệu về trạng thái chờ xử lý và xóa lỗi cũ<br>6. Sau khi lưu thay đổi, hệ thống chạy lại xử lý nền<br>7. Hệ thống thông báo đã nhận yêu cầu xử lý lại<br>8. Giao diện hiển thị kết quả xử lý lại |
| Luồng sự kiện thay thế | TH1. Không có quyền hoặc tài liệu không tồn tại<br>- 5b. Hệ thống báo lỗi xử lý lại<br>- 8. Giao diện hiển thị kết quả xử lý lại |
| Hậu điều kiện | - Nếu yêu cầu hợp lệ, tài liệu được đưa về trạng thái chờ xử lý và tác vụ xử lý nền được khởi chạy lại. |

## 5. Xóa tài liệu

| Mục | Nội dung |
| --- | --- |
| Usecase | Xóa tài liệu |
| Tác nhân chính | Admin / Giáo viên |
| Mô tả | Admin hoặc người upload xóa tài liệu khỏi hệ thống, bao gồm file lưu trữ, đoạn nội dung và liên kết nguồn tham khảo. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền `ADMIN` hoặc `TEACHER`. |
| Luồng sự kiện chính | 1. Admin/Giáo viên chọn tài liệu cần xóa<br>2. Giao diện gửi yêu cầu `DELETE /api/documents/{documentId}`<br>3. Hệ thống kiểm tra quyền Admin/Giáo viên<br>4. Hệ thống tìm tài liệu và kiểm tra chủ sở hữu<br>5a. Admin hoặc người upload: hệ thống xóa file gốc và các file xử lý trong MinIO Storage<br>6. Hệ thống xóa đoạn nội dung, liên kết nguồn và tài liệu<br>7. Hệ thống thông báo xóa tài liệu thành công<br>8. Giao diện hiển thị kết quả xóa tài liệu |
| Luồng sự kiện thay thế | TH1. Không có quyền, tài liệu không tồn tại hoặc xóa file thất bại<br>- 5b. Hệ thống báo lỗi xóa tài liệu<br>- 8. Giao diện hiển thị kết quả xóa tài liệu |
| Hậu điều kiện | - Nếu xóa thành công, tài liệu, các file liên quan trong MinIO Storage, đoạn nội dung và liên kết nguồn tham khảo bị xóa khỏi hệ thống. |
