package com.lab.hv.util;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;

/** Folia follows the Android system typeface. */
public final class AppFont {
    public static Typeface regular() { return Typeface.DEFAULT; }
    public static Typeface weight(int weight, boolean italic) {
        return Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, weight, italic)
                : Typeface.create(Typeface.DEFAULT, (weight >= 600 ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0));
    }
    public static <T extends Paint> T track(T paint) { return paint; }
}
