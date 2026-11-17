package com.lab.hv.util;

import android.graphics.Color;
import android.graphics.ColorSpace;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public final class HdrColor {
    private static final float MAX_WHITE_RATIO = 2000.0f;

    private HdrColor() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static long toLinearExtendedColor(@ColorInt int color) {
        Color converted = Color.valueOf(color).convert(linearExtendedSrgb());
        float[] c = converted.getComponents();
        return Color.pack(c[0], c[1], c[2], c[3], linearExtendedSrgb());
    }

    public static long linearExtendedWhite(float whiteRatio, float alpha) {
        float white = clamp(whiteRatio, 1.0f, MAX_WHITE_RATIO);
        return Color.pack(white, white, white, clamp01(alpha), linearExtendedSrgb());
    }

    public static long blendTowardLinearExtendedWhite(@ColorInt int baseColor,
                                                      float progress,
                                                      float targetWhiteRatio) {
        Color converted = Color.valueOf(baseColor).convert(linearExtendedSrgb());
        float[] c = converted.getComponents();
        float t = clamp01(progress);
        float target = clamp(targetWhiteRatio, 1.0f, MAX_WHITE_RATIO);
        return Color.pack(
                lerp(c[0], target, t),
                lerp(c[1], target, t),
                lerp(c[2], target, t),
                c[3],
                linearExtendedSrgb()
        );
    }

    /** Scales linear-light RGB energy without shifting hue toward a synthetic white. */
    public static long scaleLinearExtendedColor(@ColorInt int baseColor, float linearRatio) {
        Color converted = Color.valueOf(baseColor).convert(linearExtendedSrgb());
        float[] c = converted.getComponents();
        float ratio = clamp(linearRatio, 1.0f, MAX_WHITE_RATIO);
        return Color.pack(
                c[0] * ratio,
                c[1] * ratio,
                c[2] * ratio,
                c[3],
                linearExtendedSrgb()
        );
    }

    /**
     * The colour at the display's peak: its linear RGB scaled together until the brightest
     * channel is {@code headroom} (the display's HDR/SDR ratio now). The channels keep their
     * proportions, so the hue stays; a fixed multiple instead could push the brightest channel
     * past what the display shows while the others still rose, and the colour drifted (a red
     * turning pink).
     */
    public static long peakLinearExtendedColor(@ColorInt int baseColor, float headroom) {
        Color converted = Color.valueOf(baseColor).convert(linearExtendedSrgb());
        float[] c = converted.getComponents();
        float brightest = Math.max(c[0], Math.max(c[1], c[2]));
        if (brightest <= 1e-6f) return Color.pack(c[0], c[1], c[2], c[3], linearExtendedSrgb());
        float scale = Math.max(1.0f, clamp(headroom, 1.0f, MAX_WHITE_RATIO) / brightest);
        return Color.pack(c[0] * scale, c[1] * scale, c[2] * scale, c[3], linearExtendedSrgb());
    }

    @NonNull
    private static ColorSpace linearExtendedSrgb() {
        return ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB);
    }

    private static float lerp(float from, float to, float progress) {
        return from + ((to - from) * progress);
    }

    private static float clamp01(float value) {
        if (value < 0.0f) return 0.0f;
        return Math.min(value, 1.0f);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        return Math.min(value, max);
    }
}
