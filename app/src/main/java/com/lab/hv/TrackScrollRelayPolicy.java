package com.lab.hv;

/** Math used to retarget a running lyric-track animation without resetting its velocity. */
final class TrackScrollRelayPolicy {

    private static final float MAX_NORMALIZED_START_SLOPE = 3.0F;

    private TrackScrollRelayPolicy() {
    }

    static float resolveNormalizedStartSlope(float velocityPxPerMs,
                                             float startY,
                                             float targetY,
                                             long durationMs) {
        float distance = targetY - startY;
        if (durationMs <= 0L
                || Math.abs(distance) < 0.001F
                || velocityPxPerMs * distance <= 0.0F) {
            return 0.0F;
        }
        float slope = velocityPxPerMs * (float) durationMs / distance;
        return clamp(slope, 0.0F, MAX_NORMALIZED_START_SLOPE);
    }

    /**
     * Cubic Hermite handoff with the supplied starting slope and a zero end slope.
     * Position and first derivative are continuous at a retarget, while the new target still
     * settles softly without overshoot.
     */
    static float interpolate(float input, float normalizedStartSlope) {
        float t = clamp(input, 0.0F, 1.0F);
        float slope = clamp(normalizedStartSlope, 0.0F, MAX_NORMALIZED_START_SLOPE);
        float t2 = t * t;
        float t3 = t2 * t;
        return clamp(
                (slope - 2.0F) * t3
                        + (3.0F - 2.0F * slope) * t2
                        + slope * t,
                0.0F,
                1.0F
        );
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
