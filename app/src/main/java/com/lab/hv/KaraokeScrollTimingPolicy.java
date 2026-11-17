package com.lab.hv;

/** Chooses when a karaoke line should begin scrolling toward the next line. */
final class KaraokeScrollTimingPolicy {

    /**
     * A karaoke line needs a short visually stable beat after its last moving glyph. Without it,
     * consecutive short lines make the track move at the exact frame the previous word settles,
     * which reads as if that line was cut off.
     */
    static final long MIN_POST_HIGHLIGHT_HOLD_MS = 120L;
    static final float LONG_IDLE_SCROLL_START_DELAY_RATIO = 0.20F;

    private KaraokeScrollTimingPolicy() {
    }

    static long resolveAdvanceMs(long currentLineStartMs,
                                 long nextLineStartMs,
                                 long currentLineMotionEndMs,
                                 long fallbackAdvanceMs,
                                 long scrollDurationMs) {
        long safeFallbackMs = Math.max(0L, fallbackAdvanceMs);
        if (currentLineStartMs < 0L
                || nextLineStartMs <= currentLineStartMs
                || currentLineMotionEndMs == Long.MIN_VALUE
                || scrollDurationMs <= 0L) {
            return safeFallbackMs;
        }

        if (safeFallbackMs <= 0L) {
            return 0L;
        }

        // Preserve the original long-idle effect: the whole tail still drives the elegant
        // transition toward the next line. It used to start exactly when the last glyph stopped,
        // so delay that start by 20% of the available tail and use the remaining 80% as advance.
        long motionEndMs = Math.max(currentLineStartMs, currentLineMotionEndMs);
        long idleGapMs = nextLineStartMs - motionEndMs;
        if (idleGapMs >= scrollDurationMs) {
            long startDelayMs = Math.round((double) idleGapMs
                    * (double) LONG_IDLE_SCROLL_START_DELAY_RATIO);
            return Math.max(0L, idleGapMs - startDelayMs);
        }

        // Dense karaoke must keep its curve-synchronised pre-scroll. Highlight motion is rendered
        // independently, so the track may begin moving while the previous glyph tail completes;
        // waiting for the next timestamp makes every rapid handoff visibly late.
        return safeFallbackMs;
    }

    static boolean shouldCollapseIntermediateLine(long previousLineStartMs,
                                                  long currentLineStartMs,
                                                  long nextLineStartMs,
                                                  long maximumOwnWindowMs) {
        if (previousLineStartMs < 0L
                || currentLineStartMs <= previousLineStartMs
                || nextLineStartMs <= currentLineStartMs
                || maximumOwnWindowMs < 0L) {
            return false;
        }
        // The current line owns [current, next). A short incoming A→B gap says nothing about
        // whether B itself has enough time to animate before C.
        return nextLineStartMs - currentLineStartMs <= maximumOwnWindowMs;
    }
}
