package com.lab.hv;

/** Pure timing rules shared by the media-time lyrics frame scheduler. */
final class LyricsFrameTimingPolicy {

    private LyricsFrameTimingPolicy() {
    }

    static boolean isActiveInterval(long positionMs, long startMs, long endMs) {
        return startMs >= 0L && endMs > startMs && positionMs >= startMs && positionMs < endMs;
    }

    static long includeFutureBoundary(long positionMs, long currentBoundaryMs, long candidateMs) {
        if (candidateMs < 0L || candidateMs <= positionMs) {
            return currentBoundaryMs;
        }
        return Math.min(currentBoundaryMs, candidateMs);
    }

    static long resolveWakeDelayMs(long positionMs,
                                   long nextBoundaryMs,
                                   float playbackSpeed,
                                   long idleWakeMs) {
        long safeIdleWakeMs = Math.max(1L, idleWakeMs);
        if (nextBoundaryMs == Long.MAX_VALUE) {
            return safeIdleWakeMs;
        }

        long mediaDelayMs = Math.max(0L, nextBoundaryMs - positionMs);
        if (mediaDelayMs == 0L) {
            return 0L;
        }

        float safeSpeed = Float.isNaN(playbackSpeed)
                || Float.isInfinite(playbackSpeed)
                || playbackSpeed <= 0f
                ? 1f
                : playbackSpeed;
        long wallDelayMs = Math.max(1L, (long) Math.ceil(mediaDelayMs / (double) safeSpeed));
        return Math.min(safeIdleWakeMs, wallDelayMs);
    }
}
