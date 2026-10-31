# Lab 5 – Đồng bộ dữ liệu bằng Java RMI

Java 21, Maven, JavaFX 21 + CSS, SQLite JDBC. **Một Maven project duy nhất**, ba package `vn.lab5.common`, `vn.lab5.server`, `vn.lab5.client`; dùng cùng source trên máy thật và VM. Chỉ chọn vai trò chạy khác nhau, không cần tạo module hoặc sửa code theo máy.

```text
LAB5/
├── pom.xml
├── .run/                    # Server A, Server B, Client (IntelliJ)
├── src/main/java/vn/lab5/
│   ├── common/
│   ├── server/
│   └── client/
├── src/main/resources/theme.css
└── src/test/java/vn/lab5/
    ├── server/
    └── client/
```

## Build

Cài JDK 21 và Maven 3.9+, đặt JAVA_HOME trỏ đến JDK 21. Tại thư mục gốc:

```powershell
mvn clean compile
mvn test
mvn package
```

Windows có thể dùng `./mvn.ps1 clean compile`, `./mvn.ps1 test`, `./mvn.ps1 package`: script tìm Maven trong PATH hoặc Maven đi kèm IntelliJ, và ưu tiên JDK 21 trong `.tools/jdk21` nếu có. `.tools` chỉ là công cụ cục bộ, không cần chép sang máy khác. Không cần `mvn install` để chạy client. `target/lab5-1.0.0.jar` chứa các class ứng dụng và SQLite JDBC để chạy server; JavaFX native được JavaFX Maven plugin chọn theo hệ điều hành khi chạy client.

## Chạy trong IntelliJ IDEA trên mọi máy

1. Mở thư mục LAB5 hoặc `pom.xml` bằng **Open as Project**, chọn **Load Maven Project / Reload All Maven Projects**. Chỉ có một Maven project `lab5`.
2. Chọn **Project SDK = JDK 21**, language level 21. Trong Settings → Build Tools → Maven, chọn JDK 21 cho **Runner JRE** và **Importer JDK**. Tên SDK trên mỗi máy có thể khác nhau; `.idea/misc.xml` dùng tên `21` làm gợi ý, cần chọn lại nếu IDEA báo SDK chưa tồn tại.
3. Maven nhận `src/main/java` là Sources Root, `src/main/resources` là Resources Root, `src/test/java` là Test Sources Root. `LAB5.iml` cũng đã khai báo đúng các root này; không đánh dấu toàn bộ `src` làm Sources Root.
4. Ba cấu hình chia sẻ trong `.run` xuất hiện ở Run → Edit Configurations: **Server A**, **Server B**, **Client**. Các cấu hình gọi Maven bằng working directory `$PROJECT_DIR$`, không gắn đường dẫn JDK/Maven của máy tác giả.
5. Trên máy A, chọn **Server A**, trong phần Maven Runner/Environment variables của cấu hình đặt `SERVER_HOST` = IP/hostname LAN máy A và `PEER_HOST` = IP/hostname máy B. Trên máy B chọn **Server B** và đặt ngược lại. Có thể dùng biến môi trường hệ điều hành rồi mở lại IntelliJ. Không lưu địa chỉ LAN cụ thể trong source hoặc cấu hình chia sẻ.
6. A dùng `A.db`, B dùng `B.db` tại thư mục dự án của từng máy. Cùng port 1099/1100 dùng được vì hai server chạy trên hai máy độc lập. Nếu chạy chung một máy, sửa các `-Dserver.port`, `-Dserver.servicePort`, `-Dserver.peerPort` trong cấu hình B/A cho phù hợp ví dụ bên dưới.
7. Trên VM chọn **Client**, nhập IP A hoặc B và port registry trong giao diện. Không cần biến môi trường server cho Client.

Server A/B chạy bằng `compile exec:exec`; Exec Maven Plugin chỉ khởi động một JVM server riêng bằng JDK của Maven Runner và classpath hiện tại, không thay đổi logic RMI. Có thể ghi đè địa chỉ bằng `-Dserver.host=... -Dserver.peerHost=...` trong command line của cấu hình thay cho biến môi trường. Hai địa chỉ phải được cung cấp trước khi chạy.

## Hai máy vật lý

Ví dụ mạng LAN: A `192.168.1.10`, B `192.168.1.20`. Thay bằng địa chỉ thực lấy từ `ipconfig`. Mỗi máy dùng thư mục database riêng, giữ nguyên ID và file DB khi restart. Chỉ chạy một tiến trình cho mỗi file DB.

Máy A, từ gốc dự án:

```powershell
java -jar target/lab5-1.0.0.jar --id A --host 192.168.1.10 --port 1099 --service-port 1100 --peer-host 192.168.1.20 --peer-port 1099 --db A.db
```

Máy B:

```powershell
java -jar target/lab5-1.0.0.jar --id B --host 192.168.1.20 --port 1099 --service-port 1100 --peer-host 192.168.1.10 --peer-port 1099 --db B.db
```

Client trên VMware (JDK 21, Maven):

```powershell
mvn compile javafx:run
```

Hoặc Windows có script: `./mvn.ps1 compile javafx:run`.
Nhập IP A hoặc B và port registry `1099`, chọn Kết nối. Chọn hàng để sửa/xóa, Tạo mới để bỏ lựa chọn và thêm. Làm mới để lấy thay đổi từ server; client không tự polling. Mỗi thao tác RMI chạy trong Task với executor nền; JavaFX Application Thread chỉ cập nhật controls.

`--host` là địa chỉ mà client/peer truy cập được, được đặt vào `java.rmi.server.hostname` **trước khi export**. Không dùng localhost cho chạy khác máy. `--service-port` là cổng remote object cố định, cần mở bên cạnh cổng registry. Tất cả 7 tùy chọn đều bắt buộc. Thư mục cha của DB phải tồn tại. ID A/B phải khác nhau; DB lưu ID để ngăn chạy nhầm.

## Demo trên một máy

Mở hai terminal:

```powershell
java -jar target/lab5-1.0.0.jar --id A --host 127.0.0.1 --port 1099 --service-port 1100 --peer-host 127.0.0.1 --peer-port 2099 --db A.db
java -jar target/lab5-1.0.0.jar --id B --host 127.0.0.1 --port 2099 --service-port 2100 --peer-host 127.0.0.1 --peer-port 1099 --db B.db
```

Chạy client, kết nối lần lượt localhost:1099 và localhost:2099. Dừng server bằng Ctrl+C; chạy lại đúng lệnh cũ để giữ dữ liệu.

## Luồng RMI và đồng bộ

1. Server tạo registry trên `--port`, export Service trên `--service-port`, bind tên `Notes`. Không cần chạy lệnh `rmiregistry` riêng.
2. Client lookup stub, gọi `ping/list/save/delete`. DTO Note là Serializable, UUID định danh toàn cục; có title, content, updatedAt (epoch millisecond), version, deleted và origin (server tạo revision).
3. Thay đổi cục bộ được ghi trong **một transaction SQLite**: cập nhật notes + UPSERT outbox. WAL và synchronous=FULL bật. Outbox giữ trạng thái mới nhất cho mỗi UUID, gộp các lần sửa chưa gửi. Đây là đồng bộ trạng thái, không phải nhật ký mọi phím gõ.
4. Worker chạy mỗi 2 giây, lookup peer, gửi outbox rồi chỉ xóa hàng đúng revision đã được peer xác nhận. Peer chỉ trả về sau khi commit. Nếu mất phản hồi, gửi lại an toàn nhờ merge idempotent. Xác nhận revision cũ không xóa revision mới vừa sửa.
5. Sau đó kéo snapshot peer và đẩy snapshot local (bao gồm tombstone). Đối chiếu định kỳ giúp server mới tham gia và phục hồi sau gián đoạn. `receive` không ghi outbox và không gọi ngược peer, nên không có vòng lặp RPC hay khuếch đại hàng đợi. Worker không giữ khóa DB trong lúc gọi mạng.
6. Peer offline: CRUD local vẫn hoạt động, outbox còn trên đĩa qua restart. Worker thử lại tự động. Kết nối lại sẽ đồng bộ hai chiều, không cần client bấm nút sync. Timeout RMI được cấu hình 5 giây cho response/connect/handshake; DNS/OS có thể có thời gian chờ riêng.

### Xung đột

Mỗi sửa/xóa tăng version của bản đang có; so sánh thứ tự từ trái sang phải `(version, updatedAt, origin)`, **bộ lớn hơn thắng**. origin so sánh chuỗi phân biệt hoa/thường. Ví dụ cùng v2 và cùng thời gian, B thắng A. Timestamp không đứng trước version; đồng hồ lệch chỉ ảnh hưởng khi cùng version. Cần đồng bộ giờ hai máy để demo trực quan, không dựa vào giờ để đảm bảo hội tụ.

Client gửi expectedVersion để chặn ghi đè từ form cũ trên cùng server. Khi hai server bị chia mạng, cả hai có thể chấp nhận sửa và xử lý bằng quy tắc trên khi nối lại. Mọi revision thua khi merge được giữ trong bảng SQLite `conflicts` (không có UI khôi phục). Revision xóa là tombstone: ẩn khỏi list nhưng vẫn đồng bộ; không xóa vật lý. Update/delete đồng thời cũng theo cùng quy tắc, không có ưu tiên xóa tuyệt đối. Dữ liệu thắng và tombstone tồn tại sau restart. Không hỗ trợ khôi phục tự động DB bị người dùng xóa/hỏng.

Có thể xem lịch sử merge thua bằng công cụ SQLite/DataGrip:

```sql
SELECT * FROM conflicts ORDER BY id, version, updatedAt;
SELECT * FROM outbox;
SELECT * FROM notes;
```

## VMware Bridged Network và firewall

1. Tắt VM, Settings → Network Adapter → Bridged, bật Connected/Connect at power on. Trong Virtual Network Editor chọn VMnet0 bridge đến card LAN/Wi-Fi đang dùng thay vì card VPN không hoạt động.
2. Khởi động VM; dùng `ipconfig` (Windows) hoặc `ip addr` (Linux). VM, A, B cần địa chỉ cùng mạng có thể route tới nhau. Không dùng NAT/Host-only cho kịch bản LAN trực tiếp này.
3. Trên A và B, cho phép TCP inbound 1099 và 1100 từ mạng demo trong Windows Firewall (Private profile), hoặc cổng đã chọn. Không cần mở inbound cho client vì client không export callback.
4. Từ VM chạy `Test-NetConnection 192.168.1.10 -Port 1099` và `-Port 1100`, lặp lại cho B. Từ A kiểm tra cổng B và ngược lại. Ping có thể bị firewall chặn dù TCP hoạt động.
5. Nếu registry lookup được nhưng lời gọi service timeout, kiểm tra `--host`, cổng service và firewall. Nếu dùng Wi-Fi có AP/client isolation, cần đổi mạng cho phép các thiết bị giao tiếp.

## Kiểm thử và kịch bản bảo vệ

`mvn clean verify` chạy StoreTest, RmiIntegrationTest và ClientUiTest trong cùng project. Integration test tạo **hai JVM server thật**, registry thật, TCP loopback và SQLite riêng; dùng kill tiến trình để kiểm tra crash/restart. ClientUiTest chạy JavaFX controls với RMI/SQLite thật, cần desktop (Linux headless cần Xvfb); chỉ chạy test server: `mvn "-Dtest=StoreTest,RmiIntegrationTest" test`. Không thay thế kiểm thử hai máy vật lý.

| Tình huống | Thao tác demo | Kết quả mong đợi |
|---|---|---|
| CRUD A → B | Client vào A, thêm/sửa; client vào B, Làm mới sau 2–5s | UUID/nội dung/version giống nhau |
| CRUD B → A | Tạo ở B, xem A; xóa ở B, xem A | Bản mới hiện ở A, bản xóa biến mất cả hai |
| Offline | Tắt B, tạo/sửa/xóa ở A; xem outbox | A vẫn thao tác được, outbox có revision chờ |
| Restart khi offline | Tắt A, mở lại đúng DB khi B vẫn tắt | Notes/outbox còn nguyên |
| Reconnect | Mở lại B | Hai phía tự hội tụ, outbox được xác nhận |
| Xung đột thật | Chặn mạng A↔B; sửa cùng note từ client mỗi phía; nối mạng lại | Bộ (version,time,origin) lớn hơn thắng; bản thua có trong conflicts |
| Form cũ | Hai client chọn cùng note; client 1 lưu, client 2 lưu | Client 2 báo lỗi yêu cầu làm mới |
| Xóa bền vững | Xóa, restart cả hai | Note không xuất hiện lại, tombstone còn trong snapshot |
| Lỗi mạng | Nhập sai IP/port hoặc tắt server đang dùng | UI không treo, hiển thị lỗi, có thể kết nối lại |

Để tạo xung đột thật trên một máy: tắt cả hai, chạy từng server lần lượt trong khi peer tắt, sửa cùng UUID mỗi phía qua client, sau đó chạy cả hai. Không xóa DB giữa các bước. Integration test còn gửi hai revision cùng version/time qua `receive` để kiểm tra tie-break origin một cách xác định.

## Phạm vi và hạn chế

- Dành cho LAN tin cậy: RMI chưa có authentication/TLS; giao diện replication là API nội bộ có thể truy cập từ mạng demo.
- Eventual consistency; không phải transaction phân tán. Khi offline, hai phía có thể tạm thời khác nhau.
- Snapshot toàn bộ mỗi chu kỳ phù hợp bài lab nhỏ; chưa phân trang, batch hoặc tối ưu dữ liệu lớn. Tombstone/conflicts chưa có garbage collection.
- Nếu client bị mất phản hồi sau commit, cần Làm mới để kiểm tra kết quả trước khi thử thêm mới, tránh tạo hai UUID.
- Chỉ một peer cho mỗi server, không tự failover client. Chuyển server bằng nút Kết nối; form được xóa để tránh lưu nhầm.
- Kết quả build/test thực tế và phần chưa xác minh được ghi tại TEST-REPORT.md.
