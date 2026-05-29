# Đặc tả các use case chat học liệu

## 1. Tạo phiên chat

| Mục | Nội dung |
| --- | --- |
| Usecase | Tạo phiên chat |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng tạo một phiên chat mới cho một môn học để bắt đầu hỏi đáp với học liệu. |
| Tiền điều kiện | Người dùng đã đăng nhập và có quyền sử dụng chức năng chat. |
| Luồng sự kiện chính | 1. Người dùng chọn môn học và nhập tiêu đề<br>2. Giao diện gửi yêu cầu `POST /api/chat/sessions`<br>3. Hệ thống kiểm tra quyền đăng nhập<br>4. Hệ thống tìm môn học và người dùng hiện tại<br>5a. Môn học hợp lệ: hệ thống lưu phiên chat đang hoạt động<br>6. Hệ thống trả thông tin phiên chat<br>7. Giao diện hiển thị kết quả tạo phiên chat |
| Luồng sự kiện thay thế | TH1. Môn học không tồn tại hoặc người dùng chưa hợp lệ<br>- 5b. Hệ thống trả lỗi<br>- 7. Giao diện hiển thị kết quả tạo phiên chat |
| Hậu điều kiện | - Nếu tạo thành công, phiên chat mới được lưu và gắn với người dùng hiện tại. |

## 2. Xem danh sách / chi tiết phiên chat

| Mục | Nội dung |
| --- | --- |
| Usecase | Xem danh sách / chi tiết phiên chat |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xem danh sách phiên chat của mình hoặc xem chi tiết một phiên chat cụ thể. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng mở màn hình phiên chat<br>2. Hệ thống kiểm tra đăng nhập<br>3a. Xem danh sách: giao diện gửi yêu cầu `GET /api/chat/sessions`<br>4a. Hệ thống lấy danh sách phiên chat của người dùng<br>5a. Hệ thống trả danh sách phiên chat<br>6. Giao diện hiển thị kết quả xem phiên chat |
| Luồng sự kiện thay thế | TH1. Xem chi tiết và tìm thấy phiên chat<br>- 3b. Giao diện gửi yêu cầu `GET /api/chat/sessions/{sessionId}`<br>- 4b. Hệ thống tìm phiên chat thuộc người dùng<br>- 5b. Hệ thống trả chi tiết phiên chat<br>- 6. Giao diện hiển thị kết quả xem phiên chat<br><br>TH2. Xem chi tiết nhưng không tìm thấy phiên chat<br>- 3b. Giao diện gửi yêu cầu `GET /api/chat/sessions/{sessionId}`<br>- 4b. Hệ thống tìm phiên chat thuộc người dùng<br>- 5c. Hệ thống báo không tìm thấy phiên chat<br>- 6. Giao diện hiển thị kết quả xem phiên chat |
| Hậu điều kiện | - Người dùng nhận được danh sách phiên chat hoặc thông tin chi tiết phiên chat cần xem. |

## 3. Xóa phiên chat

| Mục | Nội dung |
| --- | --- |
| Usecase | Xóa phiên chat |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xóa vĩnh viễn một phiên chat. Admin có thể xóa phiên chat của người dùng khác. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng chọn phiên chat cần xóa<br>2. Giao diện gửi yêu cầu `DELETE /api/chat/sessions/{sessionId}`<br>3. Hệ thống kiểm tra đăng nhập<br>4. Hệ thống tìm phiên chat theo quyền sở hữu hoặc quyền Admin<br>5a. Có quyền: hệ thống xóa liên kết nguồn tham khảo<br>6. Hệ thống xóa tin nhắn và phiên chat<br>7. Hệ thống thông báo đã xóa phiên chat<br>8. Giao diện hiển thị kết quả xóa phiên chat |
| Luồng sự kiện thay thế | TH1. Không có quyền hoặc phiên chat không tồn tại<br>- 5b. Hệ thống trả lỗi<br>- 8. Giao diện hiển thị kết quả xóa phiên chat |
| Hậu điều kiện | - Nếu xóa thành công, phiên chat, tin nhắn và nguồn tham khảo liên quan bị xóa khỏi cơ sở dữ liệu. |

## 4. Gửi tin nhắn chat

| Mục | Nội dung |
| --- | --- |
| Usecase | Gửi tin nhắn chat |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng gửi câu hỏi trong một phiên chat. Hệ thống trả lời theo học liệu của môn học, gồm hỏi đáp thường, tóm tắt học liệu hoặc tạo câu hỏi ôn tập. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. Phiên chat thuộc người dùng hiện tại. |
| Luồng sự kiện chính | 1. Người dùng nhập câu hỏi<br>2. Giao diện gửi yêu cầu `POST /api/chat/sessions/{sessionId}/messages`<br>3. Hệ thống kiểm tra phiên chat của người dùng<br>4. Hệ thống lưu câu hỏi của người dùng<br>5. Hệ thống kiểm tra tài liệu đã sẵn sàng<br>6a. Tài liệu đã sẵn sàng<br>7a. Người dùng hỏi đáp thường<br>8. Hệ thống tạo vector tìm kiếm cho câu hỏi<br>9. Hệ thống tìm và chọn đoạn tài liệu liên quan<br>10. Hệ thống tạo câu trả lời từ tài liệu đã chọn<br>11. Hệ thống lưu phản hồi, nguồn tham khảo và nhật ký<br>12. Hệ thống trả phản hồi chat<br>13. Giao diện hiển thị phản hồi chat |
| Luồng sự kiện thay thế | TH1. Tài liệu chưa sẵn sàng<br>- 6b. Tài liệu chưa sẵn sàng<br>- 7. Hệ thống lưu thông báo tài liệu chưa sẵn sàng<br>- 11. Hệ thống lưu phản hồi và nhật ký<br>- 12. Hệ thống trả phản hồi chat<br>- 13. Giao diện hiển thị phản hồi chat<br><br>TH2. Người dùng yêu cầu tóm tắt học liệu và đã có tóm tắt<br>- 6a. Tài liệu đã sẵn sàng<br>- 7b. Người dùng yêu cầu tóm tắt học liệu<br>- 8. Hệ thống xác định phần/chương/mục cần tóm tắt<br>- 8a. Đã có tóm tắt<br>- 9. Hệ thống dùng bản tóm tắt tạo sẵn<br>- 11. Hệ thống lưu phản hồi, nguồn tham khảo và nhật ký<br>- 12. Hệ thống trả phản hồi chat<br>- 13. Giao diện hiển thị phản hồi chat<br><br>TH3. Người dùng yêu cầu tóm tắt học liệu nhưng chưa có tóm tắt<br>- 6a. Tài liệu đã sẵn sàng<br>- 7b. Người dùng yêu cầu tóm tắt học liệu<br>- 8. Hệ thống xác định phần/chương/mục cần tóm tắt<br>- 8b. Chưa có tóm tắt<br>- 9. Hệ thống tóm tắt bằng nội dung học liệu<br>- 11. Hệ thống lưu phản hồi, nguồn tham khảo và nhật ký<br>- 12. Hệ thống trả phản hồi chat<br>- 13. Giao diện hiển thị phản hồi chat<br><br>TH4. Người dùng yêu cầu tạo câu hỏi ôn tập và đã có bộ câu hỏi<br>- 6a. Tài liệu đã sẵn sàng<br>- 7c. Người dùng yêu cầu tạo câu hỏi ôn tập<br>- 8. Hệ thống xác định phần/chương/mục cần tạo câu hỏi<br>- 8a. Đã có bộ câu hỏi<br>- 9. Hệ thống dùng bộ câu hỏi tạo sẵn<br>- 11. Hệ thống lưu phản hồi, nguồn tham khảo và nhật ký<br>- 12. Hệ thống trả phản hồi chat<br>- 13. Giao diện hiển thị phản hồi chat<br><br>TH5. Người dùng yêu cầu tạo câu hỏi ôn tập nhưng chưa có bộ câu hỏi<br>- 6a. Tài liệu đã sẵn sàng<br>- 7c. Người dùng yêu cầu tạo câu hỏi ôn tập<br>- 8. Hệ thống xác định phần/chương/mục cần tạo câu hỏi<br>- 8b. Chưa có bộ câu hỏi<br>- 9. Hệ thống tạo câu hỏi ngay<br>- 11. Hệ thống lưu phản hồi, nguồn tham khảo và nhật ký<br>- 12. Hệ thống trả phản hồi chat<br>- 13. Giao diện hiển thị phản hồi chat |
| Hậu điều kiện | - Câu hỏi của người dùng và phản hồi của hệ thống được lưu vào lịch sử chat. Nguồn tham khảo và nhật ký xử lý được lưu nếu có. |

## 5. Lịch sử chat

| Mục | Nội dung |
| --- | --- |
| Usecase | Lịch sử chat |
| Tác nhân chính | Người dùng |
| Mô tả | Người dùng xem toàn bộ lịch sử tin nhắn của một phiên chat theo thứ tự thời gian. |
| Tiền điều kiện | Người dùng đã đăng nhập vào hệ thống. |
| Luồng sự kiện chính | 1. Người dùng mở lịch sử chat<br>2. Giao diện gửi yêu cầu `GET /api/chat/sessions/{sessionId}/history`<br>3. Hệ thống tìm phiên chat thuộc người dùng hiện tại<br>4a. Tìm thấy: hệ thống lấy tin nhắn theo thời gian tạo<br>5. Hệ thống trả danh sách tin nhắn<br>6. Giao diện hiển thị lịch sử chat |
| Luồng sự kiện thay thế | TH1. Phiên chat không tồn tại hoặc không thuộc người dùng hiện tại<br>- 4b. Hệ thống trả lỗi<br>- 6. Giao diện hiển thị lịch sử chat |
| Hậu điều kiện | - Người dùng nhận được lịch sử tin nhắn của phiên chat cần xem. |
