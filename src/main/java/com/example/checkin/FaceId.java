package com.example.checkin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Face ID как Spring-сервис: регистрация лица и поиск человека по фото.
 * Использует бины {@link FaceEngine} и {@link FaceMath}.
 *
 * Пример в контроллере:
 * <pre>
 *   byte[] emb = faceId.enroll(selfie.getBytes());          // сохранить в БД
 *   faceId.put(eventId, guestId, emb);                      // загрузить в память
 *   FaceId.Match m = faceId.identify(eventId, photo.getBytes());
 *   if (m.ok()) ... m.personId()
 * </pre>
 */
@Service
public class FaceId {

    public enum Status { MATCH, NO_FACE, UNKNOWN, AMBIGUOUS, EMPTY }

    /** Результат поиска. personId и score заполнены только при MATCH. */
    public record Match(Status status, String personId, float score, String reason) {
        public boolean ok() { return status == Status.MATCH; }
    }

    private final FaceEngine engine;
    private final FaceMath math;
    private final float threshold;
    private final float margin;
    /** Ограничение одновременных запусков модели, чтобы не перегрузить CPU и память. */
    private final Semaphore slots;
    /** Группа (мероприятие) -> (id человека -> нормированный вектор). */
    private final Map<Object, Map<String, float[]>> groups = new ConcurrentHashMap<>();

    public FaceId(FaceEngine engine,
                  FaceMath math,
                  @Value("${FACE_THRESHOLD:0.40}") float threshold,
                  @Value("${FACE_MARGIN:0.05}") float margin,
                  @Value("${FACE_MAX_PARALLEL:2}") int maxParallel) {
        this.engine = engine;
        this.math = math;
        this.threshold = threshold;
        this.margin = margin;
        this.slots = new Semaphore(Math.max(1, maxParallel));
    }

    // ---------- регистрация ----------

    /**
     * Строит отпечаток лица по селфи. В кадре должно быть ровно одно лицо.
     * @return вектор в байтах (little-endian float32) — удобно хранить в BLOB
     * @throws FaceEngine.FaceException если лицо не найдено или их несколько
     */
    public byte[] enroll(byte[] image) {
        return toBytes(embed(image, true));
    }

    /** Добавляет или обновляет человека в группе поиска. */
    public void put(Object groupId, String personId, byte[] embedding) {
        groups.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                .put(personId, math.l2normalize(toFloats(embedding)));
    }

    /** Полностью заменяет группу, например при старте приложения из БД. */
    public void load(Object groupId, Map<String, byte[]> embeddings) {
        Map<String, float[]> m = new ConcurrentHashMap<>();
        embeddings.forEach((id, e) -> m.put(id, math.l2normalize(toFloats(e))));
        groups.put(groupId, m);
    }

    public void remove(Object groupId, String personId) {
        Map<String, float[]> m = groups.get(groupId);
        if (m != null) m.remove(personId);
    }

    public void clear(Object groupId) {
        groups.remove(groupId);
    }

    // ---------- распознавание ----------

    /** Ищет человека по фото в группе. Если в кадре несколько лиц, берётся самое крупное. */
    public Match identify(Object groupId, byte[] image) {
        Map<String, float[]> people = groups.getOrDefault(groupId, Map.of());
        if (people.isEmpty()) return new Match(Status.EMPTY, null, 0, "Нет зарегистрированных лиц");
        float[] probe;
        try {
            probe = embed(image, false);
        } catch (FaceEngine.FaceException e) {
            return new Match(Status.NO_FACE, null, 0, e.getMessage());
        }
        String bestId = null;
        float bestScore = -2, second = -2;
        for (Map.Entry<String, float[]> p : people.entrySet()) {
            float s = math.dot(p.getValue(), probe);
            if (s > bestScore) { second = bestScore; bestScore = s; bestId = p.getKey(); }
            else if (s > second) second = s;
        }
        if (bestScore < threshold)
            return new Match(Status.UNKNOWN, null, bestScore, "Лицо не найдено среди зарегистрированных");
        if (people.size() > 1 && bestScore - second < margin)
            return new Match(Status.AMBIGUOUS, null, bestScore, "Не удалось однозначно определить человека");
        return new Match(Status.MATCH, bestId, bestScore, null);
    }

    /** Проверка «это тот же человек?» (1:1). */
    public boolean verify(byte[] image, byte[] storedEmbedding) {
        float[] probe = embed(image, false);
        return math.dot(probe, math.l2normalize(toFloats(storedEmbedding))) >= threshold;
    }

    private float[] embed(byte[] image, boolean requireSingle) {
        if (image == null || image.length == 0) throw new FaceEngine.FaceException("Пустое изображение");
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Прервано", e);
        }
        try {
            return engine.embedding(image, requireSingle);
        } finally {
            slots.release();
        }
    }

    // ---------- преобразование вектора ----------

    public static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) bb.putFloat(x);
        return bb.array();
    }

    public static float[] toFloats(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }
}
