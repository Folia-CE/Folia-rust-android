package com.lab.hv.util;

public final class NativeKernels {
    static { System.loadLibrary("folia_bridge"); }
    public static int lyricPreloadQuota(long period, long elapsed, long cost, int remaining) {
        return nativeLyricPreloadQuota(period, elapsed, cost, remaining);
    }
    public static float motionValue(float from, float to, float fraction) {
        return nativeMotionValue(from, to, fraction);
    }
    public static int motionArgb(int from, int to, float t) { return nativeMotionArgb(from, to, t); }
    public static void motionBatch(float[] rows, int count) {
        if (count < 0 || count > rows.length / 4) throw new IllegalArgumentException("Motion batch size");
        if (nativeMotionBatch(rows, count)) return;
        for (int i = 0; i < count * 4; i += 4) rows[i + 3] = rows[i] + (rows[i + 1] - rows[i]) * rows[i + 2];
    }
    private static native int nativeLyricPreloadQuota(long period, long elapsed, long cost, int remaining);
    private static native float nativeMotionValue(float from, float to, float fraction);
    private static native boolean nativeMotionBatch(float[] rows, int count);
    private static native int nativeMotionArgb(int from, int to, float t);
}
