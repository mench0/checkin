package com.example.checkin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Чистая математика без внешних библиотек: выравнивание лица, NMS, сравнение векторов. */
public final class FaceMath {

    private FaceMath() {}

    /** Эталонные точки ArcFace для кропа 112x112: глаза, нос, уголки рта. */
    public static final float[] ARCFACE_DST = {
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f
    };

    /**
     * Подобие (масштаб + поворот + сдвиг), переводящее src в dst по МНК.
     * Возвращает {a, b, tx, ty}: x' = a*x - b*y + tx, y' = b*x + a*y + ty.
     */
    public static double[] estimateSimilarity(float[] src, float[] dst) {
        int n = src.length / 2;
        double mx = 0, my = 0, dmx = 0, dmy = 0;
        for (int i = 0; i < n; i++) {
            mx += src[2 * i]; my += src[2 * i + 1];
            dmx += dst[2 * i]; dmy += dst[2 * i + 1];
        }
        mx /= n; my /= n; dmx /= n; dmy /= n;
        double na = 0, nb = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double sx = src[2 * i] - mx, sy = src[2 * i + 1] - my;
            double tx = dst[2 * i] - dmx, ty = dst[2 * i + 1] - dmy;
            na += sx * tx + sy * ty;
            nb += sx * ty - sy * tx;
            den += sx * sx + sy * sy;
        }
        if (den < 1e-9) throw new IllegalArgumentException("degenerate landmarks");
        double a = na / den, b = nb / den;
        double tx = dmx - (a * mx - b * my);
        double ty = dmy - (b * mx + a * my);
        return new double[]{a, b, tx, ty};
    }

    /**
     * Вырезает выровненное лицо size x size из ARGB-пикселей (ширина w, высота h).
     * Возвращает тензор NCHW (RGB), нормализованный как (v - 127.5) / 127.5.
     */
    public static float[] warpToTensor(int[] argb, int w, int h, double[] m, int size) {
        double a = m[0], b = m[1], tx = m[2], ty = m[3];
        double det = a * a + b * b;
        int plane = size * size;
        float[] out = new float[3 * plane];
        for (int v = 0; v < size; v++) {
            for (int u = 0; u < size; u++) {
                double px = u - tx, py = v - ty;
                double x = (a * px + b * py) / det;
                double y = (-b * px + a * py) / det;
                float[] rgb = bilinear(argb, w, h, x, y);
                int idx = v * size + u;
                out[idx] = (rgb[0] - 127.5f) / 127.5f;
                out[plane + idx] = (rgb[1] - 127.5f) / 127.5f;
                out[2 * plane + idx] = (rgb[2] - 127.5f) / 127.5f;
            }
        }
        return out;
    }

    private static float[] bilinear(int[] argb, int w, int h, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        float[] res = new float[3];
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                int xi = x0 + dx, yi = y0 + dy;
                if (xi < 0 || yi < 0 || xi >= w || yi >= h) continue;
                double wgt = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                int p = argb[yi * w + xi];
                res[0] += (float) (((p >> 16) & 255) * wgt);
                res[1] += (float) (((p >> 8) & 255) * wgt);
                res[2] += (float) ((p & 255) * wgt);
            }
        }
        return res;
    }

    /** Найденное лицо: рамка, уверенность, 5 ключевых точек (x0,y0,...,x4,y4). */
    public record Face(float x1, float y1, float x2, float y2, float score, float[] kps) {
        public float area() {
            return Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        }
    }

    public static float iou(Face p, Face q) {
        float ix = Math.max(0, Math.min(p.x2(), q.x2()) - Math.max(p.x1(), q.x1()));
        float iy = Math.max(0, Math.min(p.y2(), q.y2()) - Math.max(p.y1(), q.y1()));
        float inter = ix * iy;
        float union = p.area() + q.area() - inter;
        return union <= 0 ? 0 : inter / union;
    }

    public static List<Face> nms(List<Face> faces, float thresh) {
        List<Face> sorted = new ArrayList<>(faces);
        sorted.sort(Comparator.comparingDouble(Face::score).reversed());
        List<Face> keep = new ArrayList<>();
        for (Face f : sorted) {
            boolean ok = true;
            for (Face k : keep) {
                if (iou(f, k) > thresh) { ok = false; break; }
            }
            if (ok) keep.add(f);
        }
        return keep;
    }

    public static float[] l2normalize(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        double n = Math.sqrt(s);
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (float) (v[i] / (n + 1e-12));
        return r;
    }

    public static float dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return (float) s;
    }
}
