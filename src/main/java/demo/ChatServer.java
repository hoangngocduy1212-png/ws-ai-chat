package demo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo WebSocket + AI streaming.
 *
 * Trình duyệt <--WebSocket--> ChatServer <--HTTP streaming--> Ollama (AI chạy cục bộ)
 *
 * Giao thức tin nhắn (JSON):
 *   client -> server: {"type":"ask","text":"..."}   hỏi AI
 *                     {"type":"cancel"}              dừng câu trả lời đang chạy
 *   server -> client: {"type":"info","text":"..."}   thông tin hệ thống
 *                     {"type":"token","text":"..."}  một đoạn chữ của câu trả lời
 *                     {"type":"done","cancelled":false}  kết thúc câu trả lời
 *                     {"type":"error","text":"..."}  lỗi
 */
public class ChatServer extends WebSocketServer {

    private static final int PORT = 8080;
    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2:1b");
    private static final boolean FORCE_MOCK = "1".equals(System.getenv("MOCK"));

    /** Trạng thái riêng của mỗi kết nối WebSocket. */
    private static class Session {
        final JsonArray history = new JsonArray();          // lịch sử hội thoại gửi cho AI
        final AtomicBoolean busy = new AtomicBoolean(false); // đang trả lời hay không
        volatile boolean cancelled = false;
        volatile InputStream stream;                         // luồng đang đọc từ Ollama

        void cancel() {
            cancelled = true;
            InputStream s = stream;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();

    public ChatServer(int port) {
        super(new InetSocketAddress(port));
        setConnectionLostTimeout(30); // thư viện tự gửi PING/PONG để phát hiện kết nối chết
    }

    // ---------- Các sự kiện WebSocket ----------

    @Override
    public void onStart() {
        System.out.println("WebSocket server chạy tại ws://localhost:" + PORT);
        System.out.println(FORCE_MOCK ? "Chế độ: MÔ PHỎNG" : "Chế độ: Ollama, model " + MODEL);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new Session());
        System.out.println("[+] Kết nối mới: " + conn.getRemoteSocketAddress()
                + " (đang có " + sessions.size() + " kết nối)");
        send(conn, "info", "Đã kết nối tới server. Hãy đặt câu hỏi!");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = sessions.remove(conn);
        if (s != null) s.cancel();
        System.out.println("[-] Đóng kết nối, code=" + code + " (còn " + sessions.size() + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("<< " + message);
        Session s = sessions.get(conn);
        if (s == null) return;

        try {
            JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
            String type = msg.get("type").getAsString();

            if (type.equals("ask")) {
                String text = msg.get("text").getAsString().trim();
                if (text.isEmpty()) return;
                if (!s.busy.compareAndSet(false, true)) {
                    send(conn, "error", "AI đang trả lời, hãy đợi hoặc bấm Dừng.");
                    return;
                }
                // Chạy ở luồng riêng để không chặn việc nhận tin nhắn (ví dụ lệnh cancel)
                pool.submit(() -> answer(conn, s, text));
            } else if (type.equals("cancel")) {
                s.cancel();
            }
        } catch (Exception e) {
            send(conn, "error", "Tin nhắn không hợp lệ: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("Lỗi: " + ex);
    }

    // ---------- Xử lý câu hỏi ----------

    private void answer(WebSocket conn, Session s, String question) {
        s.cancelled = false;
        addMessage(s.history, "user", question);
        StringBuilder reply = new StringBuilder();

        try {
            if (FORCE_MOCK) {
                streamMock(conn, s, question, reply);
            } else {
                try {
                    streamOllama(conn, s, reply);
                } catch (IOException e) {
                    // Chưa nhận được chữ nào và không phải do người dùng dừng -> Ollama chưa chạy
                    if (reply.length() == 0 && !s.cancelled) {
                        send(conn, "info", "Không kết nối được Ollama, chuyển sang chế độ mô phỏng.");
                        streamMock(conn, s, question, reply);
                    } else if (!s.cancelled) {
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            send(conn, "error", "Lỗi khi gọi AI: " + e.getMessage());
        } finally {
            if (reply.length() > 0) addMessage(s.history, "assistant", reply.toString());
            JsonObject done = new JsonObject();
            done.addProperty("type", "done");
            done.addProperty("cancelled", s.cancelled);
            safeSend(conn, done.toString());
            s.stream = null;
            s.busy.set(false);
        }
    }

    /** Gọi Ollama ở chế độ streaming: mỗi dòng trả về là một JSON chứa một đoạn chữ. */
    private void streamOllama(WebSocket conn, Session s, StringBuilder reply)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("messages", s.history);
        body.addProperty("stream", true);

        HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Ollama trả về HTTP " + resp.statusCode()
                    + " (đã chạy 'ollama pull " + MODEL + "' chưa?)");
        }
        s.stream = resp.body();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(s.stream, StandardCharsets.UTF_8))) {
            String line;
            while (!s.cancelled && (line = reader.readLine()) != null) {
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                if (obj.has("message")) {
                    String piece = obj.getAsJsonObject("message").get("content").getAsString();
                    if (!piece.isEmpty()) {
                        reply.append(piece);
                        send(conn, "token", piece); // đẩy ngay về trình duyệt qua WebSocket
                    }
                }
                if (obj.has("done") && obj.get("done").getAsBoolean()) break;
            }
        } catch (IOException e) {
            if (!s.cancelled) throw e; // đóng luồng do cancel thì bỏ qua
        }
    }

    /** Giả lập AI: trả về từng từ với độ trễ nhỏ, dùng khi chưa có Ollama. */
    private void streamMock(WebSocket conn, Session s, String question, StringBuilder reply)
            throws InterruptedException {
        String text = "Đây là câu trả lời mô phỏng cho câu hỏi \"" + question + "\". "
                + "Mỗi từ được server đẩy về trình duyệt qua một frame WebSocket riêng, "
                + "nên bạn thấy chữ hiện ra dần thay vì chờ cả câu. "
                + "Hãy thử bấm nút Dừng giữa chừng để thấy tính full-duplex: "
                + "client có thể gửi lệnh lên server trong khi server vẫn đang gửi dữ liệu xuống.";
        for (String word : text.split(" ")) {
            if (s.cancelled) break;
            reply.append(word).append(' ');
            send(conn, "token", word + " ");
            Thread.sleep(80);
        }
    }

    // ---------- Hàm tiện ích ----------

    private static void addMessage(JsonArray history, String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        history.add(m);
    }

    private void send(WebSocket conn, String type, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("text", text);
        safeSend(conn, o.toString());
    }

    private void safeSend(WebSocket conn, String json) {
        try {
            conn.send(json);
        } catch (Exception e) {
            // Client đã ngắt kết nối giữa chừng -> dừng việc sinh câu trả lời
            Session s = sessions.get(conn);
            if (s != null) s.cancelled = true;
        }
    }

    public static void main(String[] args) {
    System.setOut(new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
            java.nio.charset.StandardCharsets.UTF_8));
    System.setErr(new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
            java.nio.charset.StandardCharsets.UTF_8));
    new ChatServer(PORT).start();
}
}
