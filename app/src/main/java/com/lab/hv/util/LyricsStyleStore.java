package com.lab.hv.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How the scrolling lyrics look and sit in time: text size (a share of the built-in size), font
 * weight, how strongly the lines are pulled along on a line change, where the sung line rests
 * (near the top, or in the middle), and a delay that moves every line later (or earlier, when
 * negative) against the song.
 *
 * <p>Changes reach listeners at once (the settings sliders preview live while dragged) and are
 * saved when {@code persist} is set.
 */
public final class LyricsStyleStore {

    public static final int SIZE_MIN_PERCENT = 60;
    public static final int SIZE_MAX_PERCENT = 160;
    public static final int SIZE_DEFAULT_PERCENT = 100;
    public static final int WEIGHT_MIN = 100;
    public static final int WEIGHT_MAX = 900;
    public static final int WEIGHT_DEFAULT = 400;
    public static final int DELAY_MIN_MS = -5000;
    public static final int DELAY_MAX_MS = 5000;
    public static final int DELAY_DEFAULT_MS = 0;
    public static final int PULL_MIN_PERCENT = 0;
    public static final int PULL_MAX_PERCENT = 200;
    public static final int PULL_DEFAULT_PERCENT = 100;
    /** The sung line rests near the top; lines it has passed leave. */
    public static final int ANCHOR_TOP = 0;
    /** The sung line rests in the middle; lines it has passed stay, fading with distance. */
    public static final int ANCHOR_CENTER = 1;

    private static final String PREFS = "lyrics_style";
    private static final String KEY_SIZE = "size_percent";
    private static final String KEY_WEIGHT = "weight";
    private static final String KEY_DELAY = "delay_ms";
    private static final String KEY_PULL = "pull_percent";
    private static final String KEY_ANCHOR = "scroll_anchor";

    public interface Listener {
        @MainThread
        void onLyricsStyleChanged(@NonNull LyricsStyleStore store);
    }

    @Nullable private static volatile LyricsStyleStore instance;

    @NonNull private final SharedPreferences prefs;
    @NonNull private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private int sizePercent;
    private int weight;
    private int delayMs;
    private int pullPercent;
    private int anchor;
    @Nullable private Typeface typeface;
    private int typefaceWeight = -1;
    /** The app font's generation the cached typeface was made in. */

    @NonNull
    public static LyricsStyleStore get(@NonNull Context context) {
        LyricsStyleStore current = instance;
        if (current != null) return current;
        synchronized (LyricsStyleStore.class) {
            if (instance == null) instance = new LyricsStyleStore(context.getApplicationContext());
            return instance;
        }
    }

    private LyricsStyleStore(@NonNull Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sizePercent = clamp(prefs.getInt(KEY_SIZE, SIZE_DEFAULT_PERCENT), SIZE_MIN_PERCENT, SIZE_MAX_PERCENT);
        weight = clamp(prefs.getInt(KEY_WEIGHT, WEIGHT_DEFAULT), WEIGHT_MIN, WEIGHT_MAX);
        delayMs = clamp(prefs.getInt(KEY_DELAY, DELAY_DEFAULT_MS), DELAY_MIN_MS, DELAY_MAX_MS);
        pullPercent = clamp(prefs.getInt(KEY_PULL, PULL_DEFAULT_PERCENT), PULL_MIN_PERCENT, PULL_MAX_PERCENT);
        anchor = clamp(prefs.getInt(KEY_ANCHOR, ANCHOR_TOP), ANCHOR_TOP, ANCHOR_CENTER);
        // The lyrics' font is the app's at the chosen weight: a new app font reaches them like a new weight.
    }

    /** How strongly the lines behind are pulled along on a line change (100 = as tuned). */
    public int getPullPercent() {
        return pullPercent;
    }

    public float getPullScale() {
        return pullPercent / 100f;
    }

    /** Cheap to apply, so it reaches the lyrics live while dragged too. */
    @MainThread
    public void setPullPercent(int value, boolean persist) {
        int safe = clamp(value, PULL_MIN_PERCENT, PULL_MAX_PERCENT);
        if (persist) prefs.edit().putInt(KEY_PULL, safe).apply();
        if (safe == pullPercent) return;
        pullPercent = safe;
        notifyListeners();
    }

    /** Where the sung line rests: {@link #ANCHOR_TOP} or {@link #ANCHOR_CENTER}. */
    public int getAnchor() {
        return anchor;
    }

    public boolean isCenterAnchor() {
        return anchor == ANCHOR_CENTER;
    }

    @MainThread
    public void setAnchor(int value) {
        int safe = clamp(value, ANCHOR_TOP, ANCHOR_CENTER);
        prefs.edit().putInt(KEY_ANCHOR, safe).apply();
        if (safe == anchor) return;
        anchor = safe;
        notifyListeners();
    }

    public int getSizePercent() {
        return sizePercent;
    }

    /** The text size as a factor of the built-in one. */
    public float getSizeScale() {
        return sizePercent / 100f;
    }

    public int getWeight() {
        return weight;
    }

    /** Positive: the lyrics come later than the song; negative: earlier. */
    public int getDelayMs() {
        return delayMs;
    }

    /** The lyrics' font at the chosen weight (the system font at 400 is the built-in look). */
    @NonNull
    public Typeface getTypeface() {
        Typeface current = typeface;
        if (current == null || typefaceWeight != weight) {
            current = weight == WEIGHT_DEFAULT ? AppFont.regular() : AppFont.weight(weight, false);
            typeface = current;
            typefaceWeight = weight;
        }
        return current;
    }

    @MainThread
    public void setSizePercent(int value, boolean persist) {
        int safe = clamp(value, SIZE_MIN_PERCENT, SIZE_MAX_PERCENT);
        if (persist) prefs.edit().putInt(KEY_SIZE, safe).apply();
        if (safe == sizePercent) return;
        sizePercent = safe;
        notifyListeners();
    }

    @MainThread
    public void setWeight(int value, boolean persist) {
        int safe = clamp(value, WEIGHT_MIN, WEIGHT_MAX);
        if (persist) prefs.edit().putInt(KEY_WEIGHT, safe).apply();
        if (safe == weight) return;
        weight = safe;
        notifyListeners();
    }

    @MainThread
    public void setDelayMs(int value, boolean persist) {
        int safe = clamp(value, DELAY_MIN_MS, DELAY_MAX_MS);
        if (persist) prefs.edit().putInt(KEY_DELAY, safe).apply();
        if (safe == delayMs) return;
        delayMs = safe;
        notifyListeners();
    }

    public void addListener(@NonNull Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener listener : listeners) listener.onLyricsStyleChanged(this);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
