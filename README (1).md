# Pixi Chat: demo WebSocket + AI (Java)

Ứng dụng chat với AI dùng giao thức **WebSocket**, làm cho môn Lập trình mạng.
Câu trả lời của AI được server đẩy về trình duyệt **từng đoạn chữ** qua các frame WebSocket, và người dùng có thể bấm **Dừng** giữa chừng (minh họa tính full-duplex).

```
Trình duyệt  <--WebSocket-->  ChatServer (Java)  <--HTTP streaming-->  Ollama (AI chạy trên máy)
```

## Tính năng

- Server WebSocket viết bằng Java (thư viện Java-WebSocket), mỗi kết nối có phiên và lịch sử hội thoại riêng
- Streaming từng token từ mô hình AI về trình duyệt
- Nút Dừng gửi lệnh `cancel` lên server khi AI đang trả lời
- Tự kết nối lại khi mất kết nối, ping/pong tự động
- Khung hiển thị frame WebSocket theo thời gian thực để phân tích giao thức
- Chế độ mô phỏng: nếu chưa có Ollama, server tự trả lời giả để vẫn demo được
- Giao diện pixel dễ thương

## Yêu cầu

- **JDK 17 trở lên** (ví dụ [Eclipse Temurin](https://adoptium.net))
- **VS Code** + extension *Extension Pack for Java* (khuyến nghị), hoặc **Maven** nếu chạy bằng dòng lệnh
- (Tùy chọn) **[Ollama](https://ollama.com)** để dùng AI thật

## Cách chạy

### 1. Tải project

Bấm nút **Code → Download ZIP** rồi giải nén, hoặc:

```
git clone <link-repo-này>
```

### 2. (Tùy chọn) Chuẩn bị AI thật

```
ollama pull llama3.2:1b
```

Đảm bảo Ollama đang chạy (mở ứng dụng Ollama, hoặc vào `http://localhost:11434` thấy dòng *Ollama is running*).
Muốn dùng model khác, đặt biến môi trường `OLLAMA_MODEL`, ví dụ `qwen2.5:3b`.

### 3. Chạy server

**Cách 1, VS Code:** mở thư mục project, đợi thanh trạng thái hiện *Java: Ready*, mở `src/main/java/demo/ChatServer.java` và bấm **Run** phía trên hàm `main`.

**Cách 2, dòng lệnh (cần Maven):**

```
mvn compile exec:java
```

Khi thấy dòng `ws://localhost:8080` trong console là server đã chạy.

### 4. Mở giao diện

Mở file `index.html` bằng trình duyệt. Khi góc trên bên phải hiện **Đã kết nối** là có thể chat.

> Nếu chưa cài Ollama, server sẽ tự chuyển sang chế độ mô phỏng. Muốn ép dùng chế độ này, đặt biến môi trường `MOCK=1` trước khi chạy.

## Giao thức tin nhắn (JSON)

| Hướng | Tin nhắn | Ý nghĩa |
|---|---|---|
| client → server | `{"type":"ask","text":"..."}` | Hỏi AI |
| client → server | `{"type":"cancel"}` | Dừng câu trả lời đang chạy |
| server → client | `{"type":"token","text":"..."}` | Một đoạn chữ của câu trả lời |
| server → client | `{"type":"done","cancelled":false}` | Kết thúc câu trả lời |
| server → client | `{"type":"info"\|"error","text":"..."}` | Thông tin hoặc lỗi |

## Phân tích giao thức

- Mở DevTools, tab **Network**, lọc **WS**, tải lại trang: xem handshake `Upgrade: websocket`, `Sec-WebSocket-Key`, mã `101 Switching Protocols`.
- Tab **Messages** hiển thị từng frame, trùng với khung log bên phải giao diện.
- Dùng Wireshark bắt gói trên cổng `8080` (lọc `websocket`) để xem frame text, ping/pong và frame close.

## Cấu trúc thư mục

```
ws-ai-chat/
├── pom.xml
├── index.html
└── src/main/java/demo/ChatServer.java
```

## Công nghệ

Java 17, [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket), Gson, Ollama, HTML/CSS/JavaScript thuần.
