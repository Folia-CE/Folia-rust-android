package com.lab.xui.Interpolator;

public final class BezierControl {
    public static final int PRESET_QUICK_INDEX_GLIDE = 8;
    public static final double[] SPD_UP = {0.0, 0.18, 0.24, 0.67, 0.76, 1.0};
    public static final double[] SPD_DN = {0.0, 0.5833, 0.7, 1.0};
    public static final double[] NATURAL = {0.0, 0.1, 0.3, 0.5, 0.85, 0.94, 1.0};
    public static final double[] ATTACK_SMOOTH = {
            0.0, 0.0041, 0.8, 0.82, 0.86, 0.9, 0.98, 0.982, 0.985, 0.987, 0.992, 0.996, 0.999, 1.0
    };
    public static final double[] LYRICS = {
            0.0, 0.0020, 0.0042, 0.0071, 0.0078, 0.0083, 0.0095, 0.0108, 0.87, 0.89, 0.91, 0.95, 0.98, 0.982, 0.985, 0.987, 0.992, 0.996, 0.999, 1.0
    };
    public static final double[] LYRICS_LATE_ACCEL = {
            0.0, 0.0, 0.00003551, 0.00009396, 0.00018741, 0.00032017,
            0.00049592, 0.00071794, 0.00098916, 0.00131230, 0.00168986, 0.00212419,
            0.00261749, 0.00317187, 0.00378929, 0.00447167, 0.00522082, 0.00603849,
            0.00692635, 0.00788605, 0.00891913, 0.01002714, 0.01121154, 0.01247376,
            0.01381522, 0.01523726, 0.01674121, 0.01832837, 0.02000000, 0.58000000,
            0.64000000, 0.71500000, 0.79000000, 0.85500000, 0.90800000, 0.94800000,
            0.97400000, 0.98900000, 0.99600000, 0.99840000, 0.99925000, 0.99965000,
            0.99988000, 1.0
    };
    public static final double[] SMOOTH_ATTACK = {0.0, 0.1, 1.0};
    public static final double[] ICE_REBOUND = {
            0.0, 0.09, 0.18, 0.27, 0.36, 0.45, 0.54, 0.63, 0.72, 0.76, 0.8, 0.84, 0.88, 0.92, 0.95, 0.955, 0.958, 0.97, 0.985, 0.996, 0.999, 1.0
    };
    public static final double[] ICE_REBOUND_ATTACK = {
            0.00, 0.05, 0.12, 0.22, 0.35, 0.50, 0.66, 0.80, 0.92, 0.98,
            1.02, 1.08, 1.12, 1.15, 1.10, 1.07, 1.04, 1.02, 1.01,
            1.00, 0.995, 1.002, 1.008, 1.004, 1.000
    };
    public static final double[] QUICK = {0.0, 0.86, 0.96, 0.99, 1.0};
    /**
     * Responsive ease-out used when a compact indicator has to accept a new destination
     * before its previous transition finishes. The non-zero opening slope prevents repeated
     * retargeting from making the indicator feel stuck, while the long tail softens arrival.
     */
    public static final double[] QUICK_INDEX_GLIDE = {
            0.0, 0.18, 0.46, 0.72, 0.88, 0.97, 1.0
    };

    private BezierControl() {
    }

    public static BezierInterpolator getBezierInterpolator(int bezierID) {
        BezierInterpolator interpolator = new BezierInterpolator();

        switch (bezierID) {
            case 0:
                interpolator.control(SPD_UP);
                break;
            case 1:
                interpolator.control(ATTACK_SMOOTH);
                break;
            case 2:
                interpolator.control(ICE_REBOUND);
                break;
            case 3:
                interpolator.control(QUICK);
                break;
            case 4:
                interpolator.control(SMOOTH_ATTACK);
                break;
            case 5:
                interpolator.control(LYRICS);
                break;
            case 6:
                interpolator.control(ICE_REBOUND_ATTACK);
                break;
            case 7:
                interpolator.control(LYRICS_LATE_ACCEL);
                break;
            case PRESET_QUICK_INDEX_GLIDE:
                interpolator.control(QUICK_INDEX_GLIDE);
                break;
            default:
                interpolator.control(NATURAL);
                break;
        }

        return interpolator;
    }
}
