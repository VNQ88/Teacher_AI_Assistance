# Đặc tả các use case quản lý người dùng

## 1. Admin tạo người dùng

| Mục | Nội dung |
| --- | --- |
| Usecase | Admin tạo người dùng |
| Tác nhân chính | Admin |
| Mô tả | Admin tạo tài khoản người dùng mới với email, họ tên, mật khẩu và vai trò được chỉ định. |
| Tiền điều kiện | Admin đã đăng nhập và có quyền `ADMIN`. |
| Luồng sự kiện chính | 1. Admin nhập thông tin người dùng mới<br>2. Giao diện gửi yêu cầu `POST /api/user`<br>3. Hệ thống kiểm tra quyền Admin<br>4. Hệ thống kiểm tra email và vai trò<br>5a. Hợp lệ: hệ thống lưu người dùng đã kích hoạt<br>6. Hệ thống trả thông tin người dùng vừa tạo<br>7. Giao diện hiển thị kết quả tạo người dùng |
| Luồng sự kiện thay thế | TH1. Email đã tồn tại hoặc vai trò không hợp lệ<br>- 5b. Hệ thống trả lỗi<br>- 7. Giao diện hiển thị kết quả tạo người dùng |
| Hậu điều kiện | - Tài khoản người dùng mới được lưu thành công trong cơ sở dữ liệu. |

## 2. Admin xem danh sách / chi tiết người dùng

| Mục | Nội dung |
| --- | --- |
| Usecase | Admin xem danh sách / chi tiết người dùng |
| Tác nhân chính | Admin |
| Mô tả | Admin xem danh sách người dùng theo phân trang hoặc xem chi tiết một người dùng cụ thể. |
| Tiền điều kiện | Admin đã đăng nhập và có quyền `ADMIN`. |
| Luồng sự kiện chính | 1. Admin chọn chức năng xem người dùng<br>2. Hệ thống kiểm tra quyền Admin<br>3a. Xem danh sách: giao diện gửi yêu cầu `GET /api/user/list`<br>4a. Hệ thống lấy danh sách người dùng theo trang<br>5a. Hệ thống trả danh sách người dùng<br>6. Giao diện hiển thị kết quả xem người dùng |
| Luồng sự kiện thay thế | TH1. Xem chi tiết và tìm thấy người dùng<br>- 3b. Giao diện gửi yêu cầu `GET /api/user/{userId}`<br>- 4b. Hệ thống tìm người dùng theo mã người dùng<br>- 5b. Hệ thống trả chi tiết người dùng<br>- 6. Giao diện hiển thị kết quả xem người dùng<br><br>TH2. Xem chi tiết nhưng không tìm thấy người dùng<br>- 3b. Giao diện gửi yêu cầu `GET /api/user/{userId}`<br>- 4b. Hệ thống tìm người dùng theo mã người dùng<br>- 5c. Hệ thống báo không tìm thấy người dùng<br>- 6. Giao diện hiển thị kết quả xem người dùng |
| Hậu điều kiện | - Admin nhận được danh sách người dùng hoặc thông tin chi tiết người dùng cần xem. |

## 3. Xem và cập nhật hồ sơ cá nhân

| Mục | Nội dung |
| --- | --- |
| Usecase | Xem và cập nhật hồ sơ cá nhân |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xem hồ sơ hiện tại và cập nhật thông tin cá nhân khi có quyền sửa. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng mở trang hồ sơ<br>2a. Xem hiện tại: giao diện gửi yêu cầu `GET /api/user/current`<br>3. Hệ thống tìm người dùng đang đăng nhập<br>4. Hệ thống trả hồ sơ hiện tại<br>8. Giao diện hiển thị kết quả hồ sơ |
| Luồng sự kiện thay thế | TH1. Cập nhật hồ sơ và có quyền sửa<br>- 2b. Giao diện gửi yêu cầu `PUT /api/user/{userId}`<br>- 3. Hệ thống kiểm tra quyền sửa<br>- 4a. Có quyền: hệ thống tìm người dùng cần cập nhật<br>- 6. Hệ thống lưu thông tin hồ sơ<br>- 7. Hệ thống trả hồ sơ đã cập nhật<br>- 8. Giao diện hiển thị kết quả hồ sơ<br><br>TH2. Cập nhật hồ sơ nhưng không có quyền sửa<br>- 2b. Giao diện gửi yêu cầu `PUT /api/user/{userId}`<br>- 3. Hệ thống kiểm tra quyền sửa<br>- 4b. Hệ thống trả lỗi không có quyền<br>- 8. Giao diện hiển thị kết quả hồ sơ |
| Luồng tùy chọn | Điều kiện: người dùng có đổi email khi cập nhật hồ sơ<br>- 5. Hệ thống kiểm tra email mới trước khi lưu |
| Hậu điều kiện | - Hồ sơ hiện tại được hiển thị hoặc thông tin hồ sơ được cập nhật thành công. |

## 4. Đổi mật khẩu

| Mục | Nội dung |
| --- | --- |
| Usecase | Đổi mật khẩu |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng đổi mật khẩu bằng cách nhập mật khẩu cũ, mật khẩu mới và xác nhận mật khẩu mới. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng nhập mật khẩu cũ và mật khẩu mới<br>2. Giao diện gửi yêu cầu `POST /api/user/change-password`<br>3. Hệ thống kiểm tra xác nhận mật khẩu mới<br>4a. Xác nhận khớp: hệ thống tìm người dùng hiện tại<br>5. Hệ thống so khớp mật khẩu cũ<br>6a. Mật khẩu cũ đúng: hệ thống mã hóa mật khẩu mới<br>7. Hệ thống lưu mật khẩu mới<br>8. Hệ thống thông báo đổi mật khẩu thành công<br>9. Giao diện hiển thị kết quả đổi mật khẩu |
| Luồng sự kiện thay thế | TH1. Xác nhận mật khẩu mới không khớp<br>- 4b. Hệ thống trả lỗi xác nhận mật khẩu<br>- 9. Giao diện hiển thị kết quả đổi mật khẩu<br><br>TH2. Mật khẩu cũ sai<br>- 4a. Xác nhận khớp: hệ thống tìm người dùng hiện tại<br>- 5. Hệ thống so khớp mật khẩu cũ<br>- 6b. Hệ thống trả lỗi mật khẩu cũ<br>- 9. Giao diện hiển thị kết quả đổi mật khẩu |
| Hậu điều kiện | - Mật khẩu mới được mã hóa và lưu thành công trong cơ sở dữ liệu. |

## 5. Admin cấp quyền Teacher

| Mục | Nội dung |
| --- | --- |
| Usecase | Admin cấp quyền Teacher |
| Tác nhân chính | Admin |
| Mô tả | Admin cấp thêm quyền Teacher cho một người dùng chưa có quyền Teacher. |
| Tiền điều kiện | Admin đã đăng nhập và có quyền `ADMIN`. |
| Luồng sự kiện chính | 1. Admin chọn người dùng cần cấp quyền<br>2. Giao diện gửi yêu cầu `POST /api/user/{userId}/become-teacher`<br>3. Hệ thống kiểm tra quyền Admin<br>4. Hệ thống tìm người dùng và quyền Teacher<br>5a. Tìm thấy: hệ thống kiểm tra quyền hiện tại<br>6a. Người dùng chưa có quyền Teacher: hệ thống thêm quyền Teacher và lưu<br>7. Hệ thống thông báo đã cấp quyền Teacher<br>8. Giao diện hiển thị kết quả cấp quyền |
| Luồng sự kiện thay thế | TH1. Người dùng đã có quyền Teacher<br>- 5a. Tìm thấy: hệ thống kiểm tra quyền hiện tại<br>- 6b. Hệ thống trả lỗi nghiệp vụ<br>- 8. Giao diện hiển thị kết quả cấp quyền<br><br>TH2. Không tìm thấy người dùng hoặc quyền Teacher<br>- 5b. Hệ thống trả lỗi không tìm thấy<br>- 8. Giao diện hiển thị kết quả cấp quyền |
| Hậu điều kiện | - Người dùng được gán thêm quyền Teacher. |

## 6. Admin xóa người dùng

| Mục | Nội dung |
| --- | --- |
| Usecase | Admin xóa người dùng |
| Tác nhân chính | Admin |
| Mô tả | Admin xóa một tài khoản người dùng khỏi hệ thống. |
| Tiền điều kiện | Admin đã đăng nhập và có quyền `ADMIN`. |
| Luồng sự kiện chính | 1. Admin chọn người dùng cần xóa<br>2. Giao diện gửi yêu cầu `DELETE /api/user/{userId}`<br>3. Hệ thống kiểm tra quyền Admin<br>4. Hệ thống tìm người dùng cần xóa<br>5a. Tìm thấy: hệ thống kiểm tra không xóa chính mình<br>6a. Hợp lệ: hệ thống xóa người dùng<br>7. Hệ thống thông báo đã xóa người dùng<br>8. Giao diện hiển thị kết quả xóa người dùng |
| Luồng sự kiện thay thế | TH1. Người dùng cần xóa là tài khoản đang đăng nhập<br>- 5a. Tìm thấy: hệ thống kiểm tra không xóa chính mình<br>- 6b. Hệ thống trả lỗi không được xóa chính mình<br>- 8. Giao diện hiển thị kết quả xóa người dùng<br><br>TH2. Người dùng không tồn tại<br>- 5b. Hệ thống báo không tìm thấy người dùng<br>- 8. Giao diện hiển thị kết quả xóa người dùng |
| Hậu điều kiện | - Tài khoản người dùng được xóa khỏi cơ sở dữ liệu. |
