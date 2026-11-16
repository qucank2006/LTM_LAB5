# LAB5_RMI — Java 21, Maven, Java RMI, MySQL

Một Maven project, một pom.xml, packaging JAR. Source ở src/main/java, theme ở
src/main/resources, src/test/java dành cho kiểm thử về sau. Không có sourceDirectory override.

## Thành phần
- com.lab5.rmi.server.ServerMain: dùng chung cho hai Server.
- com.lab5.rmi.client.ClientMain: dùng chung cho hai Client.
- MySQL Connector/J 9.1.0; FlatLaf 3.5.4.
- Maven Compiler Plugin 3.13.0 (release 21); Maven JAR Plugin 3.4.2.
- FlatLaf Light với palette Catppuccin Latte trong resources/themes/latte.properties.
- Không dùng JAR Connector chép tay hoặc module riêng theo máy.

## Chuẩn bị hai máy Windows trong IntelliJ IDEA
1. Cài JDK 21 LTS và MySQL trên từng máy, clone cùng repository.
2. Mở pom.xml dưới dạng Maven project. Chọn Project SDK và Maven runner JDK 21,
   dùng Reload All Maven Projects để IntelliJ nhận dependency và source roots chuẩn.
3. Trong MySQL Workbench trên TỪNG máy, chạy database/setup.sql. Tạo user MySQL riêng
   theo ví dụ cuối file, rồi sửa db.user và db.password. Không kết nối DB của máy kia.
   Đây là schema mới lab5_bank; không tự di chuyển dữ liệu schema cũ.
4. IP ví dụ: máy 1 = 192.168.1.10, máy 2 = 192.168.1.11. Thay bằng IPv4 LAN thực tế.
   Sửa rmi.host, primary.host, peer.host trong config/server1.properties và
   config/server2.properties; sửa server.host trong hai file client.
5. Thay replication.secret bằng cùng một chuỗi bí mật trên hai Server.
   Giữ server1 PRIMARY, server2 STANDBY và primary.id=server1.
6. Cho phép inbound TCP 1099 (registry) và 1100 (remote object) của Java trên Windows
   Firewall cho mạng LAN riêng ở cả hai máy. Hai máy dùng cùng số port vì khác IP.
   Không cần chạy rmiregistry riêng; Server tự tạo registry.
7. Tạo Application Run Configuration Server trong IntelliJ:
   main class com.lab5.rmi.server.ServerMain; JRE 21; classpath Maven module LAB5_RMI;
   Working directory là repository root.
   Máy 1 Program arguments: config/server1.properties.
   Máy 2 Program arguments: config/server2.properties.
8. Tạo Application Run Configuration Client tương tự:
   main class com.lab5.rmi.client.ClientMain.
   Máy 1 Program arguments: config/client1.properties.
   Máy 2 Program arguments: config/client2.properties.
9. Người dùng tự khởi chạy Server 1, Server 2, rồi Client ở từng máy.
   Mỗi máy chạy một Server và một Client. Không chạy thêm bản Server dùng cùng database.
   JAR Maven là JAR thông thường, không phải fat JAR; README hướng dẫn chạy bằng
   classpath dependency do IntelliJ quản lý, không dùng java -jar.

## Chức năng và nguyên tắc nhất quán
Đăng nhập bằng tài khoản demo 1001 hoặc 1002, mật khẩu 123456. Phiên hết hạn sau một giờ,
mất khi Primary khởi động lại. Client có nạp (DEPOSIT), rút (WITHDRAW), chuyển
(TRANSFER), số dư, lịch sử 200 giao dịch gần nhất, đăng xuất.

Tất cả thao tác Client đến Standby được chuyển tiếp sang Primary, gồm cả đọc và
đăng nhập; Standby không trả số dư cũ khi Primary mất kết nối. Primary là node ghi
duy nhất. Không có bầu chọn, không tự thăng cấp khi partition và không có nút promote.
Nếu Primary ngừng, Client ngừng phục vụ cho đến khi Primary trở lại. Đây là đánh đổi
availability để tránh hai node tự trở thành Primary trong mô hình hai máy.

Cấu hình định danh Primary phải nhất quán ở cả hai máy. Không đổi hai file thành PRIMARY
và không chạy thêm Primary dùng bản sao database khác. Đây là Primary tĩnh, không phải
consensus/quorum hoặc cơ chế fencing chống quản trị viên cấu hình sai. MySQL advisory
lock ngăn hai tiến trình dùng cùng local database. Thay Primary thủ công chỉ được làm
sau khi đã dừng/cô lập Primary cũ, xác nhận outbox đã được ACK đầy đủ và dữ liệu Standby
đã đồng bộ; phiên Client cần đăng nhập lại. Chưa cung cấp công cụ chuyển vai trò.

Mọi giao dịch dùng BigDecimal và DECIMAL(19,2). Khóa ledger_state bằng SELECT FOR UPDATE
tuần tự hóa thao tác, hai số dư của chuyển khoản được commit cùng lịch sử và outbox.
Không có fallback lưu tiền trong bộ nhớ nếu MySQL lỗi.

Worker gửi từng event theo sequence, nhận ACK rồi đánh dấu delivered. Khi lỗi, giữ event
trong outbox, tăng attempts, lưu last_error và retry sau 3 giây; event lỗi chặn event sau.
Standby áp dụng balances và event trong cùng transaction, yêu cầu sequence kế tiếp,
nhận lại cùng ID/nội dung thì ACK, không áp dụng tiền lần thứ hai. Không xóa outbox đã
gửi; database Standby phải được giữ bền vững. Nếu mất hoặc reset DB Standby sau khi ACK,
cần dừng dịch vụ và restore từ backup Primary nhất quán; chưa có snapshot/bootstrap tự động.

Replication bất đồng bộ: Primary có thể commit trong khi Standby offline. Giao dịch thành
công nghĩa là local commit, chưa bảo đảm bản sao đã ACK; mất vĩnh viễn Primary trước
replication có thể mất giao dịch chưa gửi. Không promote Standby đang lag.

Client giữ request ID và payload sau lỗi mạng chưa rõ kết quả. Dùng "Thử lại cùng ID",
không nhập lại một giao dịch mới. Nếu phiên hết hạn/Primary restart, đăng nhập lại cùng
tài khoản rồi retry. Server đối chiếu nội dung ID trong MySQL và trả kết quả cũ.
Pending request hiện chỉ giữ trong RAM Client: không đóng Client khi kết quả chưa rõ;
ghi lại ID và kiểm tra lịch sử nếu Client bị đóng. Chưa có hàng đợi request bền vững ở Client.

## Dashboard và tác vụ nền
Server Dashboard hiển thị node, role, IP/port, MySQL, sequence, số outbox pending và
trạng thái replication/retry. Client Dashboard có đăng nhập và các thao tác ngân hàng.
RMI/JDBC chạy ngoài Swing EDT qua SwingWorker hoặc scheduler; UI cập nhật trên EDT.
Một remote object dùng port cố định 1100 cho Bank và Replica, registry port 1099.
Timeout RMI được đặt qua thuộc tính transport của JDK; JDBC có connect/socket timeout.

## Giới hạn và kiểm chứng do người dùng thực hiện
Không có Terminal, build, compile, test hoặc chương trình nào được agent chạy khi chỉnh sửa.
Chưa xác minh khả năng biên dịch, tải dependency, giao diện thực tế, MySQL, firewall hoặc RMI LAN.
Người dùng tự xác minh trong IntelliJ:
- đăng nhập đúng/sai; số dư; nạp/rút/chuyển; từ chối âm, quá hai chữ số thập phân,
  thiếu tiền, tài khoản nhận sai và chuyển cho chính mình;
- hai Client thao tác đồng thời; chuyển khoản không làm mất tổng tiền;
- tắt Standby, phát sinh giao dịch, bật lại và quan sát outbox về 0;
- thử lại cùng ID sau mất ACK, restart Server và so sánh hai database;
- ngắt mạng: Standby không được ghi độc lập; dừng Primary thì Client báo lỗi;
- MySQL mất kết nối, khôi phục, lịch sử và số dư không bị ghi một nửa.
Chưa có automated tests; không có cam kết các tình huống trên đã pass.

Ứng dụng dành cho bài lab trên LAN tin cậy. Mật khẩu demo SHA-256 chưa có salt/KDF,
RMI chưa có TLS, replication dùng shared secret, chưa có rate limiting hoặc audit quản trị.
Không dùng dữ liệu/ngân hàng thực tế. Không đưa mật khẩu thật vào repository công khai.
