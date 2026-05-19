# Đặc tả các use case môn học

## 1. Xem danh sách / chi tiết môn học

| Mục | Nội dung |
| --- | --- |
| Usecase | Xem danh sách / chi tiết môn học |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xem danh sách môn học hoặc xem thông tin chi tiết của một môn học cụ thể. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng mở màn hình môn học<br>2. Hệ thống kiểm tra đăng nhập<br>3a. Xem danh sách: giao diện gửi yêu cầu `GET /api/subjects`<br>4a. Hệ thống lấy danh sách môn học<br>5a. Hệ thống trả danh sách môn học<br>6. Giao diện hiển thị kết quả xem môn học |
| Luồng sự kiện thay thế | TH1. Xem chi tiết và tìm thấy môn học<br>- 3b. Giao diện gửi yêu cầu `GET /api/subjects/{subjectId}`<br>- 4b. Hệ thống tìm môn học theo mã định danh<br>- 5b. Hệ thống trả chi tiết môn học<br>- 6. Giao diện hiển thị kết quả xem môn học<br><br>TH2. Xem chi tiết nhưng không tìm thấy môn học<br>- 3b. Giao diện gửi yêu cầu `GET /api/subjects/{subjectId}`<br>- 4b. Hệ thống tìm môn học theo mã định danh<br>- 5c. Hệ thống báo không tìm thấy môn học<br>- 6. Giao diện hiển thị kết quả xem môn học |
| Hậu điều kiện | - Người dùng nhận được danh sách môn học hoặc thông tin chi tiết môn học cần xem. |

## 2. Tạo môn học

| Mục | Nội dung |
| --- | --- |
| Usecase | Tạo môn học |
| Tác nhân chính | Admin / Giáo viên |
| Mô tả | Admin hoặc giáo viên tạo một môn học mới với tên, mã môn học, mô tả và loại môn học. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền `ADMIN` hoặc `TEACHER`. |
| Luồng sự kiện chính | 1. Admin/Giáo viên nhập thông tin môn học<br>2. Giao diện gửi yêu cầu `POST /api/subjects`<br>3. Hệ thống kiểm tra quyền Admin/Giáo viên<br>4. Hệ thống kiểm tra tên và mã môn học<br>5a. Dữ liệu hợp lệ: hệ thống lưu môn học<br>6. Hệ thống trả thông tin môn học mới<br>7. Giao diện hiển thị kết quả tạo môn học |
| Luồng sự kiện thay thế | TH1. Tên hoặc mã môn học đã tồn tại<br>- 5b. Hệ thống trả lỗi<br>- 7. Giao diện hiển thị kết quả tạo môn học |
| Hậu điều kiện | - Nếu tạo thành công, môn học mới được lưu trong cơ sở dữ liệu, gắn với người tạo hiện tại và ở trạng thái hoạt động. |

## 3. Cập nhật môn học

| Mục | Nội dung |
| --- | --- |
| Usecase | Cập nhật môn học |
| Tác nhân chính | Admin / Giáo viên |
| Mô tả | Admin hoặc giáo viên cập nhật thông tin môn học gồm tên, mô tả, trạng thái active và loại môn học. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền `ADMIN` hoặc `TEACHER`. |
| Luồng sự kiện chính | 1. Admin/Giáo viên sửa thông tin môn học<br>2. Giao diện gửi yêu cầu `PUT /api/subjects/{subjectId}`<br>3. Hệ thống kiểm tra quyền Admin/Giáo viên<br>4. Hệ thống tìm môn học và người dùng hiện tại<br>5a. Môn học tồn tại: hệ thống kiểm tra quyền chỉnh sửa<br>6a. Có quyền: hệ thống cập nhật thông tin môn học<br>7. Hệ thống trả môn học đã cập nhật<br>8. Giao diện hiển thị kết quả cập nhật môn học |
| Luồng sự kiện thay thế | TH1. Môn học tồn tại nhưng không có quyền chỉnh sửa<br>- 5a. Môn học tồn tại: hệ thống kiểm tra quyền chỉnh sửa<br>- 6b. Hệ thống trả lỗi không có quyền<br>- 8. Giao diện hiển thị kết quả cập nhật môn học<br><br>TH2. Môn học không tồn tại<br>- 5b. Hệ thống báo không tìm thấy môn học<br>- 8. Giao diện hiển thị kết quả cập nhật môn học |
| Hậu điều kiện | - Nếu cập nhật thành công, thông tin môn học được lưu trong cơ sở dữ liệu. |

## 4. Xóa môn học

| Mục | Nội dung |
| --- | --- |
| Usecase | Xóa môn học |
| Tác nhân chính | Admin / Giáo viên |
| Mô tả | Admin hoặc giáo viên xóa một môn học khỏi hệ thống. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền `ADMIN` hoặc `TEACHER`. |
| Luồng sự kiện chính | 1. Admin/Giáo viên chọn môn học cần xóa<br>2. Giao diện gửi yêu cầu `DELETE /api/subjects/{subjectId}`<br>3. Hệ thống kiểm tra quyền Admin/Giáo viên<br>4. Hệ thống tìm môn học và người dùng hiện tại<br>5a. Môn học tồn tại: hệ thống kiểm tra quyền xóa<br>6a. Có quyền: hệ thống xóa môn học<br>7. Hệ thống thông báo đã xóa môn học<br>8. Giao diện hiển thị kết quả xóa môn học |
| Luồng sự kiện thay thế | TH1. Môn học tồn tại nhưng không có quyền xóa<br>- 5a. Môn học tồn tại: hệ thống kiểm tra quyền xóa<br>- 6b. Hệ thống trả lỗi không có quyền<br>- 8. Giao diện hiển thị kết quả xóa môn học<br><br>TH2. Môn học không tồn tại<br>- 5b. Hệ thống báo không tìm thấy môn học<br>- 8. Giao diện hiển thị kết quả xóa môn học |
| Hậu điều kiện | - Nếu xóa thành công, môn học được xóa khỏi cơ sở dữ liệu. |
