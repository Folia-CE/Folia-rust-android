package com.lab.hv.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

/**
 * Centralized string lookup for custom Java-drawn UI surfaces.
 */
public final class AppStrings {
    private static volatile Context appContext;

    private AppStrings() {
    }

    public static void init(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    @NonNull
    public static String get(@StringRes int resId) {
        Context context = appContext;
        if (context == null) {
            return "";
        }
        try {
            return context.getString(resId);
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
