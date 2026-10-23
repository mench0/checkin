package com.example.checkin;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Распознавание лиц на моделях insightface (buffalo_l) через ONNX Runtime:
 * det_10g.onnx (детектор SCRFD) + w600k_r50.onnx (ArcFace, вектор 512).
 * FACE_MOCK=1 включает заглушку без моделей (только для проверки потока).
 */
public class FaceEngine implements AutoCloseable {

    public static class FaceException extends RuntimeException {
        public FaceException(String msg) { super(msg); }
    }

    private static final int DET_SIZE = 640;
    private static final int[] STRIDES = {8, 16, 32};
    private static final int ANCHORS = 2;
    private static final float DET_THRESH = 0.5f;
    private static final float NMS_THRESH = 0.4f;

    private final boolean mock;
    private OrtEnvironment env;
    private OrtSession det;
    private OrtSession rec;
    private String detInput;
    private String recInput;

    public FaceEngine(String modelsDir, boolean mock) throws OrtException {
        this.mock = mock;
        if (mock) return;
        Path detPath = Path.of(modelsDir, "det_10g.onnx");
        Path recPath = Path.of(modelsDir, "w600k_r50.onnx");
        if (!Files.exists(detPath) || !Files.exists(recPath)) {
            throw new IllegalStateException(
                    "Не найдены модели " + detPath.toAbsolutePath() + " и " + recPath.toAbsolutePath()
                            + ". Скачайте buffalo_l.zip (см. README) или запустите с FACE_MOCK=1.");
        }
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        det = env.createSession(detPath.toString(), opts);
        rec = env.createSession(recPath.toString(), opts);
        detInput = det.getInputNames().iterator().next();
        recInput = rec.getInputNames().iterator().next();
    }

    /**
     * Отпечаток лица (нормированный вектор 512).
     * @param requireSingle true при регистрации: ошибка, если в кадре несколько лиц.
     *                      false на входе: берётся самое крупное лицо.
     */
    public float[] embedding(byte[] data, boolean requireSingle) {
        if (mock) return mockEmbedding(data);
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            img = null;
        }
        if (img == null) throw new FaceException("Не удалось прочитать изображение");
        try {
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            List<FaceMath.Face> faces = detect(img);
            if (faces.isEmpty()) throw new FaceException("Лицо не найдено");
            if (requireSingle && faces.size() > 1) throw new FaceException("В кадре несколько лиц");
            FaceMath.Face face = faces.stream().max(Comparator.comparingDouble(FaceMath.Face::area)).get();
            return recognize(argb, w, h, face.kps());
        } catch (OrtException e) {
            throw new IllegalStateException("Ошибка модели: " + e.getMessage(), e);
        }
    }

    private List<FaceMath.Face> detect(BufferedImage img) throws OrtException {
        int w = img.getWidth(), h = img.getHeight();
        float ratio = Math.min((float) DET_SIZE / w, (float) DET_SIZE / h);
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        float scale = (float) nh / h;

        BufferedImage canvas = new BufferedImage(DET_SIZE, DET_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();

        int plane = DET_SIZE * DET_SIZE;
        int[] px = canvas.getRGB(0, 0, DET_SIZE, DET_SIZE, null, 0, DET_SIZE);
        float[] input = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int p = px[i];
            input[i] = (((p >> 16) & 255) - 127.5f) / 128f;
            input[plane + i] = (((p >> 8) & 255) - 127.5f) / 128f;
            input[2 * plane + i] = ((p & 255) - 127.5f) / 128f;
        }

        List<FaceMath.Face> faces = new ArrayList<>();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, DET_SIZE, DET_SIZE});
             OrtSession.Result res = det.run(Map.of(detInput, t))) {
            for (int k = 0; k < STRIDES.length; k++) {
                int s = STRIDES[k];
                FloatBuffer scores = ((OnnxTensor) res.get(k)).getFloatBuffer();
                FloatBuffer boxes = ((OnnxTensor) res.get(k + 3)).getFloatBuffer();
                FloatBuffer kps = ((OnnxTensor) res.get(k + 6)).getFloatBuffer();
                int fw = DET_SIZE / s, fh = DET_SIZE / s;
                int n = fw * fh * ANCHORS;
                for (int i = 0; i < n; i++) {
                    float score = scores.get(i);
                    if (score < DET_THRESH) continue;
                    int cell = i / ANCHORS;
                    float cx = (cell % fw) * s, cy = (cell / fw) * s;
                    float x1 = (cx - boxes.get(i * 4) * s) / scale;
                    float y1 = (cy - boxes.get(i * 4 + 1) * s) / scale;
                    float x2 = (cx + boxes.get(i * 4 + 2) * s) / scale;
                    float y2 = (cy + boxes.get(i * 4 + 3) * s) / scale;
                    float[] pts = new float[10];
                    for (int j = 0; j < 5; j++) {
                        pts[2 * j] = (cx + kps.get(i * 10 + 2 * j) * s) / scale;
                        pts[2 * j + 1] = (cy + kps.get(i * 10 + 2 * j + 1) * s) / scale;
                    }
                    faces.add(new FaceMath.Face(x1, y1, x2, y2, score, pts));
                }
            }
        }
        return FaceMath.nms(faces, NMS_THRESH);
    }

    private float[] recognize(int[] argb, int w, int h, float[] kps) throws OrtException {
        double[] m = FaceMath.estimateSimilarity(kps, FaceMath.ARCFACE_DST);
        float[] input = FaceMath.warpToTensor(argb, w, h, m, 112);
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, 112, 112});
             OrtSession.Result res = rec.run(Map.of(recInput, t))) {
            FloatBuffer fb = ((OnnxTensor) res.get(0)).getFloatBuffer();
            float[] v = new float[fb.remaining()];
            fb.get(v);
            return FaceMath.l2normalize(v);
        }
    }

    private static float[] mockEmbedding(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            long seed = ((d[0] & 0xffL) << 24) | ((d[1] & 0xffL) << 16) | ((d[2] & 0xffL) << 8) | (d[3] & 0xffL);
            Random r = new Random(seed);
            float[] v = new float[512];
            for (int i = 0; i < v.length; i++) v[i] = (float) r.nextGaussian();
            return FaceMath.l2normalize(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws OrtException {
        if (det != null) det.close();
        if (rec != null) rec.close();
    }
}
