package com.lab.hv;

import android.animation.Animator;
import android.animation.ValueAnimator;
import java.util.ArrayList;

/** ValueAnimator-compatible listeners, with the app's unscaled clock and Rust value batches.
 * No platform Animator is started. Views and their existing lifecycle own cancellation.
 */
public final class RustValueAnimator extends ValueAnimator {
    private static OnlineMotion shared;
    static OnlineMotion clock() {
        if (shared == null) { shared = new OnlineMotion(true); shared.setEnabled(true); }
        return shared;
    }
    private final ArrayList<AnimatorUpdateListener> updates = new ArrayList<>();
    private float[] values;
    private boolean integers;
    private int[] colors;
    private int colorResult;
    private OnlineMotion.Value task;
    private float fraction;
    private float result;
    private long seek;
    private boolean notifiedStart;

    public static RustValueAnimator ofFloat(float... values) {
        RustValueAnimator a = new RustValueAnimator(); a.setFloatValues(values); return a;
    }
    public static RustValueAnimator ofInt(int... values) {
        RustValueAnimator a = new RustValueAnimator(); a.setIntValues(values); return a;
    }
    public static RustValueAnimator ofArgb(int... colors) {
        if (colors.length < 2) throw new IllegalArgumentException("Two colors required");
        RustValueAnimator a = ofFloat(0f, 1f); a.colors = colors.clone(); return a;
    }
    @Override public void setFloatValues(float... next) {
        if (next.length == 0) throw new IllegalArgumentException("No animation values");
        values = next.length == 1 ? new float[]{0f, next[0]} : next.clone(); integers = false;
    }
    @Override public void setIntValues(int... next) {
        if (next.length == 0) throw new IllegalArgumentException("No animation values");
        values = new float[Math.max(2, next.length)];
        if (next.length == 1) values[1] = next[0];
        else for (int i = 0; i < next.length; i++) values[i] = next[i];
        integers = true;
    }
    @Override public void addUpdateListener(AnimatorUpdateListener listener) { updates.add(listener); }
    @Override public void removeUpdateListener(AnimatorUpdateListener listener) { updates.remove(listener); }
    @Override public void removeAllUpdateListeners() { updates.clear(); }
    @Override public Object getAnimatedValue() { if (colors != null) return Integer.valueOf(colorResult); if (integers) return Integer.valueOf((int)result); return Float.valueOf(result); }
    @Override public Object getAnimatedValue(String name) { return getAnimatedValue(); }
    @Override public float getAnimatedFraction() { return fraction; }
    @Override public long getCurrentPlayTime() { return task == null ? seek : task.getCurrentPlayTime(); }
    @Override public boolean isRunning() { return task != null && task.running; }
    @Override public boolean isStarted() { return isRunning(); }
    @Override public void setCurrentPlayTime(long time) {
        seek = Math.max(0, time); if (task != null) task.seek(seek);
    }
    @Override public void start() {
        if (isStarted()) cancel();
        notifiedStart = false;
        final OnlineMotion.Value next = clock().value(values[0], values[values.length-1]);
        next.setKeyframes(values);
        next.setDuration(getDuration()); next.setStartDelay(getStartDelay());
        next.setInterpolator(getInterpolator()); next.setRepeatCount(getRepeatCount()); next.setRepeatMode(getRepeatMode());
        next.addUpdateListener(a -> {
            if (task != a) return;
            if (!notifiedStart) { notifiedStart = true; notifyListeners(0); }
            if (task != a || !a.running) return;
            fraction = a.getAnimatedFraction(); result = a.getAnimatedValue();
            if (colors != null) {
                float slot = fraction * (colors.length - 1);
                int index = Math.max(0, Math.min(colors.length - 2, (int)slot));
                colorResult = com.lab.hv.util.NativeKernels.motionArgb(colors[index], colors[index+1], slot-index);
            }
            for (int i=0; i<updates.size() && task == a && a.running; i++) updates.get(i).onAnimationUpdate(this);
        });
        next.addListener(new OnlineMotion.Listener() {
            @Override public void onAnimationCancel(OnlineMotion.Value a) {
                if (task == a) notifyListeners(1);
            }
            @Override public void onAnimationEnd(OnlineMotion.Value a) {
                if (task != a) return;
                task = null; seek = 0L; notifyListeners(2);
            }
        });
        task = next;
        long pendingSeek = seek;
        next.start();
        if (pendingSeek > 0 && task == next) next.seek(pendingSeek);
    }
    @Override public void cancel() { if (task != null) task.cancel(); }
    @Override public void end() { if (task != null) task.end(); }
    private void notifyListeners(int event) {
        ArrayList<AnimatorListener> listeners = getListeners();
        if (listeners == null) return;
        // Only lifecycle events allocate; steady frames reuse the registered update list.
        for (AnimatorListener listener : new ArrayList<>(listeners)) {
            if (event == 0) listener.onAnimationStart(this);
            else if (event == 1) listener.onAnimationCancel(this);
            else listener.onAnimationEnd(this);
        }
    }
}
