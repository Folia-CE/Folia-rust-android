package com.lab.hv;

import androidx.annotation.NonNull;

/**
 * The scrolling lyrics' rows as a chain of springs (rust/lyricchain): the line being sung is
 * held to its place on the track, rows on the side the track moves toward follow closely, and
 * each row on the other side is tied by a spring to the row ahead of it. So a line change pulls
 * the rows behind along one after another, each a little later and further behind than the one
 * before, and they glide back into place in the same order, without bouncing.
 *
 * <p>The view moves the track; this says, frame by frame, how far each row is from its place on
 * it. Without the native library every row simply sits at its place.
 */
final class LyricLagChain {

    private static final boolean NATIVE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("folia_bridge");
            loaded = true;
        } catch (Throwable e) {
            loaded = false;
        }
        NATIVE = loaded;
    }

    private long handle;
    @NonNull private float[] tops = new float[0];
    @NonNull private int[] sources = new int[0];
    @NonNull private float[] offsets = new float[0];
    private float rowHeight;
    private boolean resting = true;

    LyricLagChain(float rowHeightPx) {
        rowHeight = Math.max(1f, rowHeightPx);
        handle = NATIVE ? nativeCreate(rowHeight) : 0L;
    }

    boolean isNative() {
        return handle != 0L;
    }

    /**
     * The rows' places on the track (px, top to bottom) and the line each belongs to; a no-op
     * when nothing changed. Offsets carry over while the rows stay the same ones.
     */
    void setRows(@NonNull float[] rowTops, @NonNull int[] rowSources, int count, float rowHeightPx) {
        boolean same = count == tops.length && Math.abs(rowHeightPx - rowHeight) < 0.01f;
        for (int i = 0; same && i < count; i++) {
            same = tops[i] == rowTops[i] && sources[i] == rowSources[i];
        }
        if (same) return;
        if (tops.length != count) {
            tops = new float[count];
            sources = new int[count];
            offsets = new float[count];
        }
        System.arraycopy(rowTops, 0, tops, 0, count);
        System.arraycopy(rowSources, 0, sources, 0, count);
        rowHeight = Math.max(1f, rowHeightPx);
        if (handle != 0L) nativeSetRows(handle, tops, sources, rowHeight);
    }

    /** The line being sung (-1: none); {@code rigid}: every row follows the track closely. */
    void setHead(int sourceIndex, boolean rigid) {
        if (handle != 0L) nativeSetHead(handle, sourceIndex, rigid ? 1 : 0);
        resting = false;
    }

    /** How strongly rows are pulled: 1 as tuned, 0 none (Settings → lyrics). */
    void setGain(float gain) {
        if (handle != 0L) nativeSetGain(handle, gain);
    }

    /** Every row at its place, still. */
    void reset(float trackY) {
        if (handle != 0L) nativeReset(handle, trackY);
        java.util.Arrays.fill(offsets, 0f);
        resting = true;
    }

    /** Moves on by {@code dtSeconds} with the track at {@code trackY}; true once at rest. */
    boolean step(float dtSeconds, float trackY) {
        if (handle == 0L || offsets.length == 0) {
            java.util.Arrays.fill(offsets, 0f);
            resting = true;
            return true;
        }
        resting = nativeStep(handle, dtSeconds, trackY, offsets) != 0;
        return resting;
    }

    boolean isResting() {
        return resting;
    }

    /** Row {@code index}'s offset from its place after the last step (px). */
    float offset(int index) {
        return index >= 0 && index < offsets.length ? offsets[index] : 0f;
    }

    void release() {
        if (handle != 0L) {
            nativeDestroy(handle);
            handle = 0L;
        }
    }

    private static native long nativeCreate(float rowHeight);

    private static native void nativeDestroy(long handle);

    private static native void nativeSetRows(long handle, float[] tops, int[] sources, float rowHeight);

    private static native void nativeSetHead(long handle, int head, int rigid);

    private static native void nativeReset(long handle, float track);

    private static native void nativeSetGain(long handle, float gain);

    private static native int nativeStep(long handle, float dt, float track, float[] out);
}
