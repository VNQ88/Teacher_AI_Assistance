# Đặc tả các use case xác thực

## 1. Đăng nhập

| Mục | Nội dung |
| --- | --- |
| Usecase | Đăng nhập |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng đăng nhập vào hệ thống bằng email và mật khẩu. |
| Tiền điều kiện | Người dùng đã có tài khoản và tài khoản đã được kích hoạt. |
| Luồng sự kiện chính | 1. Người dùng mở màn hình đăng nhập<br>2. Người dùng nhập email và mật khẩu<br>3. Giao diện gửi yêu cầu `POST /auth/login`<br>4. Hệ thống kiểm tra tài khoản trong database<br>5a. Tài khoản hợp lệ<br>6. Hệ thống tạo token đăng nhập<br>7. Hệ thống trả token đăng nhập cho giao diện<br>8. Giao diện hiển thị trạng thái đã đăng nhập |
| Luồng sự kiện thay thế | TH1. Sai email/mật khẩu hoặc tài khoản chưa kích hoạt<br>- 5b. Hệ thống trả lỗi xác thực<br>- 6. Giao diện hiển thị thông báo lỗi |
| Hậu điều kiện | - Phiên đăng nhập được thiết lập và người dùng nhận `accessToken`, `refreshToken`. |

## 2. Đăng ký tài khoản

| Mục | Nội dung |
| --- | --- |
| Usecase | Đăng ký tài khoản |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng đăng ký tài khoản Student và nhận mã kích hoạt qua email. |
| Tiền điều kiện | Người dùng có email để đăng ký và nhận mã kích hoạt. |
| Luồng sự kiện chính | 1. Người dùng nhập thông tin đăng ký<br>2. Giao diện gửi yêu cầu `POST /auth/register`<br>3. Hệ thống kiểm tra email trong database<br>4a. Email chưa tồn tại: hệ thống tạo tài khoản chưa kích hoạt<br>5. Hệ thống lưu mã kích hoạt có thời hạn vào Redis<br>6. Hệ thống gửi email kích hoạt<br>7. Hệ thống thông báo người dùng kiểm tra email<br>8. Giao diện hiển thị kết quả đăng ký |
| Luồng sự kiện thay thế | TH1. Email đã đăng ký nhưng chưa kích hoạt<br>- 4b. Hệ thống tạo lại mã kích hoạt<br>- 5. Hệ thống gửi email kích hoạt mới<br>- 6. Hệ thống thông báo người dùng kiểm tra email<br>- 8. Giao diện hiển thị kết quả đăng ký<br><br>TH2. Email đã kích hoạt<br>- 4c. Hệ thống báo email đã tồn tại<br>- 8. Giao diện hiển thị kết quả đăng ký |
| Hậu điều kiện | - Nếu đăng ký thành công, tài khoản mới ở trạng thái chờ kích hoạt và người dùng nhận email kích hoạt. |

## 3. Kích hoạt tài khoản

| Mục | Nội dung |
| --- | --- |
| Usecase | Kích hoạt tài khoản |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng dùng mã kích hoạt nhận qua email để kích hoạt tài khoản. |
| Tiền điều kiện | Người dùng đã đăng ký tài khoản nhưng tài khoản chưa được kích hoạt. Người dùng có mã kích hoạt còn hiệu lực. |
| Luồng sự kiện chính | 1. Người dùng nhập mã kích hoạt nhận qua email<br>2. Giao diện gửi yêu cầu `POST /auth/activate-account`<br>3. Hệ thống kiểm tra mã kích hoạt trong Redis<br>4. Hệ thống kích hoạt tài khoản<br>5. Hệ thống xóa mã đã dùng<br>6. Hệ thống thông báo tài khoản đã kích hoạt<br>7. Giao diện hiển thị tài khoản đã kích hoạt |
| Luồng sự kiện thay thế | TH1. Mã kích hoạt không hợp lệ hoặc đã hết hạn<br>- Hệ thống trả lỗi mã kích hoạt<br>- Giao diện hiển thị thông báo lỗi |
| Hậu điều kiện | - Nếu kích hoạt thành công, tài khoản được kích hoạt và mã OTP đã dùng bị xóa. |

## 4. Quên mật khẩu

| Mục | Nội dung |
| --- | --- |
| Usecase | Quên mật khẩu |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng yêu cầu hệ thống gửi mã đặt lại mật khẩu qua email. |
| Tiền điều kiện | Người dùng nhập email cần khôi phục mật khẩu. |
| Luồng sự kiện chính | 1. Người dùng nhập email quên mật khẩu<br>2. Giao diện gửi yêu cầu `POST /auth/forgot-password`<br>3. Hệ thống tìm tài khoản theo email<br>4c. Tài khoản đã kích hoạt: hệ thống tạo mã đặt lại mật khẩu<br>5. Hệ thống gửi email đặt lại mật khẩu<br>6. Hệ thống thông báo người dùng kiểm tra email đặt lại mật khẩu<br>7. Giao diện hiển thị kết quả yêu cầu quên mật khẩu |
| Luồng sự kiện thay thế | TH1. Email không tồn tại<br>- 4a. Hệ thống trả lỗi email không tồn tại<br>- 7. Giao diện hiển thị kết quả yêu cầu quên mật khẩu<br><br>TH2. Tài khoản chưa kích hoạt<br>- 4b. Hệ thống tạo mã kích hoạt<br>- 5. Hệ thống gửi email kích hoạt<br>- 6. Hệ thống thông báo người dùng kiểm tra email kích hoạt<br>- 7. Giao diện hiển thị kết quả yêu cầu quên mật khẩu |
| Hậu điều kiện | - Nếu yêu cầu quên mật khẩu thành công, người dùng nhận email phù hợp với trạng thái tài khoản. |

## 5. Đặt lại mật khẩu

| Mục | Nội dung |
| --- | --- |
| Usecase | Đặt lại mật khẩu |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xác minh mã đặt lại mật khẩu và cập nhật mật khẩu mới. |
| Tiền điều kiện | Người dùng có mã đặt lại mật khẩu còn hiệu lực. |
| Luồng sự kiện chính | 1. Người dùng nhập mã đặt lại mật khẩu<br>2. Giao diện gửi yêu cầu `POST /auth/verify-reset-code`<br>3. Hệ thống kiểm tra mã đặt lại mật khẩu và email<br>4. Hệ thống xác nhận mã hợp lệ<br>5. Giao diện gửi yêu cầu `POST /auth/reset-password`<br>6. Hệ thống cập nhật mật khẩu mới<br>7. Hệ thống xóa mã đã dùng<br>8. Hệ thống thông báo đặt lại mật khẩu thành công<br>9. Giao diện hiển thị đặt lại mật khẩu thành công |
| Luồng sự kiện thay thế | TH1. Mã đặt lại mật khẩu không hợp lệ, hết hạn hoặc không khớp email<br>- Hệ thống trả lỗi mã đặt lại mật khẩu<br>- Giao diện hiển thị thông báo lỗi<br><br>TH2. Mật khẩu xác nhận không khớp<br>- Hệ thống trả lỗi mật khẩu xác nhận<br>- Giao diện hiển thị thông báo lỗi |
| Hậu điều kiện | - Nếu đặt lại mật khẩu thành công, mật khẩu mới được lưu và mã OTP đã dùng bị xóa. |

## 6. Đăng xuất

| Mục | Nội dung |
| --- | --- |
| Usecase | Đăng xuất |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng đăng xuất khỏi hệ thống. Hệ thống kiểm tra token hiện tại và vô hiệu hóa token để phiên đăng nhập không còn được sử dụng. |
| Tiền điều kiện | Người dùng đã đăng nhập và giao diện đang lưu token đăng nhập hợp lệ. |
| Luồng sự kiện chính | 1. Người dùng chọn đăng xuất<br>2. Giao diện gửi yêu cầu `POST /auth/logout`<br>3. Hệ thống kiểm tra token đăng xuất<br>4. Hệ thống kiểm tra access token và refresh token<br>5a. Token hợp lệ<br>6. Hệ thống lưu token vào danh sách vô hiệu hóa trong Redis<br>7. Hệ thống thông báo đăng xuất thành công<br>8. Giao diện hiển thị trạng thái đã đăng xuất |
| Luồng sự kiện thay thế | TH1. Thiếu token hoặc token không hợp lệ<br>- 5b. Hệ thống trả lỗi đăng xuất<br>- 6. Giao diện hiển thị thông báo lỗi |
| Hậu điều kiện | - Nếu đăng xuất thành công, token của phiên hiện tại bị vô hiệu hóa và người dùng được đưa về trạng thái chưa đăng nhập. |
