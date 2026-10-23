package com.example.checkin;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class ApiController {

    /** Ошибка API: отдаётся как {"detail": "..."} — так же, как в Python-версии. */
    public static class ApiError extends RuntimeException {
        final HttpStatus status;
        public ApiError(HttpStatus status, String msg) { super(msg); this.status = status; }
    }

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(ApiError.class)
        public ResponseEntity<Map<String, Object>> api(ApiError e) {
            return ResponseEntity.status(e.status).body(Map.of("detail", e.getMessage()));
        }

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<Map<String, Object>> tooBig(MaxUploadSizeExceededException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("detail", "Фото слишком большое"));
        }
    }

    private final JdbcTemplate db;
    private final FaceEngine faces;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Value("${ADMIN_KEY:admin123}") private String adminKey;
    @Value("${PUBLIC_URL:}") private String publicUrl;
    @Value("${FACE_THRESHOLD:0.40}") private float threshold;
    @Value("${FACE_MARGIN:0.05}") private float margin;

    public ApiController(JdbcTemplate db, FaceEngine faces) {
        this.db = db;
        this.faces = faces;
    }

    @PostConstruct
    void initDb() {
        db.execute("""
                CREATE TABLE IF NOT EXISTS events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL)""");
        db.execute("""
                CREATE TABLE IF NOT EXISTS guests(
                    id TEXT PRIMARY KEY,
                    event_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    phone TEXT,
                    party_size INTEGER DEFAULT 1,
                    note TEXT,
                    table_no TEXT,
                    embedding BLOB,
                    created_at REAL NOT NULL,
                    registered_at REAL,
                    arrived_at REAL)""");
        db.execute("CREATE INDEX IF NOT EXISTS idx_guests_event ON guests(event_id)");
        Integer n = db.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
        if (n == null || n == 0) db.update("INSERT INTO events(name) VALUES ('Мероприятие')");
    }

    // ---------- helpers ----------
    private void admin(String key) {
        if (!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), adminKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiError(HttpStatus.UNAUTHORIZED, "Нет доступа");
        }
    }

    private String baseUrl() {
        if (publicUrl != null && !publicUrl.isBlank()) return publicUrl.replaceAll("/+$", "");
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private void rateLimit(HttpServletRequest req, int limit) {
        String ip = Optional.ofNullable(req.getHeader("X-Forwarded-For"))
                .map(h -> h.split(",")[0].trim()).orElse(req.getRemoteAddr());
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= limit) throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "Слишком много попыток, подождите минуту");
            q.addLast(now);
        }
    }

    private String newId() {
        byte[] b = new byte[10];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) bb.putFloat(x);
        return bb.array();
    }

    private static float[] toFloats(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }

    private boolean eventExists(long id) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM events WHERE id=?", Integer.class, id);
        return n != null && n > 0;
    }

    private static byte[] read(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (Exception e) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "Не удалось прочитать фото");
        }
    }

    // ---------- события ----------
    @GetMapping("/api/events")
    public List<Map<String, Object>> events() {
        return db.queryForList("SELECT * FROM events ORDER BY id");
    }

    @PostMapping("/api/admin/events")
    public Map<String, Object> createEvent(@RequestParam String name,
                                           @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        db.update("INSERT INTO events(name) VALUES (?)", name);
        Long id = db.queryForObject("SELECT last_insert_rowid()", Long.class);
        return Map.of("id", id, "name", name);
    }

    /** Один QR на ресторан: ведёт на страницу входа с Face ID. */
    @GetMapping(value = "/api/event/{eventId}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] venueQr(@PathVariable long eventId) throws Exception {
        if (!eventExists(eventId)) throw new ApiError(HttpStatus.NOT_FOUND, "Мероприятие не найдено");
        String url = baseUrl() + "/checkin.html?event=" + eventId;
        BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(m, "PNG", out);
        return out.toByteArray();
    }

    // ---------- админ: гости и столики ----------
    /** Каждая строка: «Имя» или «Имя; столик». Возвращает ссылки на регистрацию. */
    @PostMapping("/api/admin/guests")
    public List<Map<String, Object>> addGuests(@RequestParam("event_id") long eventId,
                                               @RequestParam String lines,
                                               @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        if (!eventExists(eventId)) throw new ApiError(HttpStatus.NOT_FOUND, "Мероприятие не найдено");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String raw : lines.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            int sep = line.indexOf(';');
            String name = (sep < 0 ? line : line.substring(0, sep)).strip();
            String table = sep < 0 ? "" : line.substring(sep + 1).strip();
            if (name.isEmpty()) continue;
            String id = newId();
            db.update("INSERT INTO guests(id,event_id,name,table_no,created_at) VALUES (?,?,?,?,?)",
                    id, eventId, name, table.isEmpty() ? null : table, now());
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", id);
            g.put("name", name);
            g.put("table_no", table.isEmpty() ? null : table);
            g.put("link", baseUrl() + "/register.html?invite=" + id);
            out.add(g);
        }
        return out;
    }

    @GetMapping("/api/admin/guests/{eventId}")
    public List<Map<String, Object>> listGuests(@PathVariable long eventId,
                                                @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id,name,phone,party_size,note,table_no,registered_at,arrived_at "
                        + "FROM guests WHERE event_id=? ORDER BY name", eventId);
        String base = baseUrl();
        for (Map<String, Object> r : rows) r.put("link", base + "/register.html?invite=" + r.get("id"));
        return rows;
    }

    @PostMapping("/api/admin/guests/{guestId}/table")
    public Map<String, Object> setTable(@PathVariable String guestId,
                                        @RequestParam(value = "table_no", defaultValue = "") String tableNo,
                                        @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        String t = tableNo.strip();
        int n = db.update("UPDATE guests SET table_no=? WHERE id=?", t.isEmpty() ? null : t, guestId);
        if (n == 0) throw new ApiError(HttpStatus.NOT_FOUND, "Гость не найден");
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/admin/guests/{guestId}")
    public Map<String, Object> deleteGuest(@PathVariable String guestId,
                                           @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        db.update("DELETE FROM guests WHERE id=?", guestId);
        return Map.of("ok", true);
    }

    /** Удаляет отпечатки лиц всех гостей мероприятия (после его окончания). */
    @PostMapping("/api/admin/events/{eventId}/purge")
    public Map<String, Object> purge(@PathVariable long eventId,
                                     @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        int n = db.update("UPDATE guests SET embedding=NULL WHERE event_id=?", eventId);
        return Map.of("cleared", n);
    }

    @GetMapping("/api/admin/stats/{eventId}")
    public Map<String, Object> stats(@PathVariable long eventId,
                                     @RequestHeader(value = "X-Admin-Key", defaultValue = "") String key) {
        admin(key);
        Map<String, Object> r = db.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(registered_at IS NOT NULL),0) AS registered, "
                        + "COALESCE(SUM(arrived_at IS NOT NULL),0) AS arrived "
                        + "FROM guests WHERE event_id=?", eventId);
        return r;
    }

    // ---------- гость: регистрация ----------
    @GetMapping("/api/invite/{invite}")
    public Map<String, Object> inviteInfo(@PathVariable String invite) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT g.name, g.phone, g.party_size, g.note, g.registered_at, e.name AS event "
                        + "FROM guests g JOIN events e ON e.id=g.event_id WHERE g.id=?", invite);
        if (rows.isEmpty()) throw new ApiError(HttpStatus.NOT_FOUND, "Приглашение не найдено");
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("name", "phone", "party_size", "note", "event")) out.put(k, r.get(k));
        out.put("registered", r.get("registered_at") != null);
        return out;
    }

    @PostMapping("/api/register")
    public Map<String, Object> register(HttpServletRequest req,
                                        @RequestParam String invite,
                                        @RequestParam String name,
                                        @RequestParam(defaultValue = "") String phone,
                                        @RequestParam(value = "party_size", defaultValue = "1") int partySize,
                                        @RequestParam(defaultValue = "") String note,
                                        @RequestParam(defaultValue = "") String consent,
                                        @RequestParam MultipartFile selfie) {
        rateLimit(req, 15);
        if (!"1".equals(consent))
            throw new ApiError(HttpStatus.BAD_REQUEST, "Нужно согласие на обработку биометрических данных");
        if (partySize < 1 || partySize > 50) throw new ApiError(HttpStatus.BAD_REQUEST, "Неверное количество гостей");
        if (name.isBlank()) throw new ApiError(HttpStatus.BAD_REQUEST, "Введите имя");
        Integer n = db.queryForObject("SELECT COUNT(*) FROM guests WHERE id=?", Integer.class, invite);
        if (n == null || n == 0) throw new ApiError(HttpStatus.NOT_FOUND, "Приглашение не найдено");
        float[] emb;
        try {
            emb = faces.embedding(read(selfie), true);
        } catch (FaceEngine.FaceException e) {
            throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        db.update("UPDATE guests SET name=?, phone=?, party_size=?, note=?, embedding=?, registered_at=? WHERE id=?",
                name.strip(), phone.strip(), partySize, note.strip(), toBytes(emb), now(), invite);
        return Map.of("ok", true);
    }

    // ---------- гость: вход в ресторане ----------
    /** Ищет гостя по лицу среди всех зарегистрированных на мероприятие и возвращает столик. */
    @PostMapping("/api/checkin")
    public Map<String, Object> checkin(HttpServletRequest req,
                                       @RequestParam("event_id") long eventId,
                                       @RequestParam MultipartFile selfie) {
        rateLimit(req, 30);
        Map<String, Object> out = new LinkedHashMap<>();
        float[] probe;
        try {
            probe = faces.embedding(read(selfie), false);
        } catch (FaceEngine.FaceException e) {
            out.put("ok", false);
            out.put("retry", true);
            out.put("reason", e.getMessage());
            return out;
        }
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id,name,table_no,embedding FROM guests WHERE event_id=? AND embedding IS NOT NULL", eventId);
        if (rows.isEmpty()) {
            out.put("ok", false);
            out.put("reason", "Нет зарегистрированных гостей");
            return out;
        }
        int best = -1;
        float bestScore = -2, second = -2;
        for (int i = 0; i < rows.size(); i++) {
            float s = FaceMath.dot(FaceMath.l2normalize(toFloats((byte[]) rows.get(i).get("embedding"))), probe);
            if (s > bestScore) { second = bestScore; bestScore = s; best = i; }
            else if (s > second) second = s;
        }
        if (bestScore < threshold) {
            out.put("ok", false);
            out.put("retry", true);
            out.put("reason", "Вас нет в списке гостей. Обратитесь к администратору.");
            return out;
        }
        if (rows.size() > 1 && bestScore - second < margin) {
            out.put("ok", false);
            out.put("reason", "Не удалось однозначно определить гостя. Обратитесь к администратору.");
            return out;
        }
        Map<String, Object> g = rows.get(best);
        db.update("UPDATE guests SET arrived_at=COALESCE(arrived_at,?) WHERE id=?", now(), g.get("id"));
        out.put("ok", true);
        out.put("name", g.get("name"));
        out.put("table", g.get("table_no"));
        out.put("score", Math.round(bestScore * 1000) / 1000.0);
        return out;
    }

    @GetMapping("/")
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/admin.html")).build();
    }
}
