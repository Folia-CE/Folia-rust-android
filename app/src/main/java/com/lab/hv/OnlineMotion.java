package com.lab.hv;

import android.animation.TimeInterpolator;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * One vsync clock for the online library. Durations use monotonic time, independent of the
 * platform animator duration scale. Interpolators remain the original Android curve evaluators;
 * they do not own a clock. All entry points and callbacks belong to the UI thread.
 */
public final class OnlineMotion implements Choreographer.FrameCallback {
    private final boolean retainPropertyDefaults;
    private final java.util.WeakHashMap<View, Defaults> defaults = new java.util.WeakHashMap<>();
    private static final class Defaults {
        long duration = 300L, delay;
        TimeInterpolator curve = DEFAULT;
    }
    OnlineMotion() { this(false); }
    OnlineMotion(boolean retainPropertyDefaults) { this.retainPropertyDefaults = retainPropertyDefaults; }

    private static final TimeInterpolator DEFAULT = new AccelerateDecelerateInterpolator();
    private final ArrayList<Task> tasks = new ArrayList<>();
    private final IdentityHashMap<View, ArrayList<Properties>> properties = new IdentityHashMap<>();
    private Task[] frameTasks = new Task[16];
    private float[] valuesBatch = new float[64];
    private boolean enabled;
    private boolean posted;
    private boolean dispatching;
    private long pausedAt = System.nanoTime();
    private long pausedTotal;

    void setEnabled(boolean next) {
        if (enabled == next) return;
        long now = System.nanoTime();
        if (next) pausedTotal += now - pausedAt;
        else pausedAt = now;
        enabled = next;
        if (!enabled && posted) {
            Choreographer.getInstance().removeFrameCallback(this);
            posted = false;
        }
        schedule();
    }

    private long now() {
        return (enabled ? System.nanoTime() : pausedAt) - pausedTotal;
    }

    private void schedule() {
        if (enabled && !posted && !dispatching && !tasks.isEmpty()) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override public void doFrame(long frameTimeNanos) {
        posted = false;
        if (!enabled) return;
        int count = tasks.size();
        if (frameTasks.length < count) frameTasks = new Task[Math.max(count, frameTasks.length * 2)];
        for (int i = 0; i < count; i++) frameTasks[i] = tasks.get(i);
        if (valuesBatch.length < count * 4) valuesBatch = new float[frameTasks.length * 4];
        for (int i = 0; i < count; i++) {
            if (frameTasks[i] instanceof Value) {
                Value value = (Value) frameTasks[i];
                value.prepared = value.running && value.prepare(frameTimeNanos - pausedTotal);
                if (value.prepared) {
                    value.endpoints(valuesBatch, i * 4);
                    value.preparedGeneration = value.generation;
                }
            }
        }
        com.lab.hv.util.NativeKernels.motionBatch(valuesBatch, count);
        dispatching = true;
        try {
            // Callbacks can cancel other tasks or start a successor. A successor begins next frame.
            for (int i = 0; i < count; i++) {
                Task task = frameTasks[i];
                frameTasks[i] = null;
                if (enabled && task.running) {
                    if (task instanceof Value) {
                        Value value = (Value) task;
                        if (value.prepared && value.generation == value.preparedGeneration)
                            value.publish(valuesBatch[i * 4 + 3]);
                    } else task.tick(frameTimeNanos - pausedTotal);
                }
            }
        } finally {
            for (int i = 0; i < count; i++) frameTasks[i] = null;
            dispatching = false;
            schedule();
        }
    }

    private abstract class Task {
        boolean running;
        long start;

        void begin() {
            if (running) tasks.remove(this);
            running = true;
            start = now();
            tasks.add(this);
            schedule();
        }

        void stop() {
            running = false;
            tasks.remove(this);
            if (tasks.isEmpty() && posted) {
                Choreographer.getInstance().removeFrameCallback(OnlineMotion.this);
                posted = false;
            }
        }

        abstract void tick(long time);
    }

    interface Update { void update(Value value); }
    static class Listener {
        public void onAnimationEnd(Value value) { }
        public void onAnimationCancel(Value value) { }
    }

    Value value(float from, float to) { return new Value(from, to); }

    final class Value extends Task {
        static final int INFINITE = -1;
        static final int REVERSE = 2;
        private final float from;
        private final float to;
        private long duration = 300L;
        private long delay;
        private int repeats;
        private int repeatMode;
        private float fraction;
        private float value;
        private float[] keyframes;
        void setKeyframes(float[] values) { keyframes = values; }
        void seek(long ms) { long time = now(); start = time - (delay + ms) * 1_000_000L; tick(time); }
        private long playTime;
        private int generation;
        private boolean prepared, done;
        private int preparedGeneration;
        private TimeInterpolator curve = DEFAULT;
        private Update update;
        private Listener listener;

        private Value(float from, float to) {
            this.from = from;
            this.to = to;
            value = from;
        }

        Value setDuration(long ms) { duration = Math.max(0L, ms); return this; }
        void setStartDelay(long ms) { delay = Math.max(0L, ms); }
        void setInterpolator(TimeInterpolator next) { curve = next; }
        void setRepeatCount(int count) { repeats = Math.max(INFINITE, count); }
        void setRepeatMode(int mode) { repeatMode = mode; }
        void addUpdateListener(Update next) { update = next; }
        void addListener(Listener next) { listener = next; }
        float getAnimatedValue() { return value; }
        float getAnimatedFraction() { return fraction; }
        long getCurrentPlayTime() { return playTime; }

        void start() {
            generation++;
            playTime = 0L;
            done = false;
            begin();
            if (delay == 0L) apply(0f);
        }

        @Override void tick(long time) {
            if (!prepare(time)) return;
            float[] single = new float[4];
            endpoints(single, 0);
            publish(com.lab.hv.util.NativeKernels.motionValue(single[0], single[1], single[2]));
        }

        private boolean prepare(long time) {
            double elapsed = (time - start) / 1_000_000.0 - delay;
            if (elapsed < 0.0) return false;
            playTime = (long) elapsed;
            double cycles = duration == 0L ? 1.0 : elapsed / duration;
            done = duration == 0L || (repeats != INFINITE && cycles >= repeats + 1.0);
            long iteration = done ? Math.max(0, repeats) : Math.max(0L, (long) Math.ceil(cycles) - 1L);
            float raw = done ? 1f : (float) (cycles - iteration);
            if (repeatMode == REVERSE && (iteration & 1L) != 0L) raw = 1f - raw;
            fraction = curve == null ? raw : curve.getInterpolation(raw);
            return true;
        }

        private void publish(float next) {
            int before = generation;
            value = next;
            if (update != null) update.update(this);
            if (done && running && generation == before) finish(false);
        }

        private void endpoints(float[] output, int offset) {
            float left = from, right = to, t = fraction;
            if (keyframes != null && keyframes.length > 2) {
                float slot = fraction * (keyframes.length - 1);
                int index = Math.max(0, Math.min(keyframes.length - 2, (int) slot));
                left = keyframes[index]; right = keyframes[index + 1]; t = slot - index;
            }
            output[offset] = left; output[offset + 1] = right; output[offset + 2] = t;
        }

        private void apply(float raw) {
            fraction = curve == null ? raw : curve.getInterpolation(raw);
            float[] single = new float[4];
            endpoints(single, 0);
            value = com.lab.hv.util.NativeKernels.motionValue(single[0], single[1], single[2]);
            if (update != null) update.update(this);
        }

        void end() {
            if (!running) return;
            int before = generation;
            playTime = duration * (Math.max(0, repeats) + 1L);
            apply(repeatMode == REVERSE && repeats > 0 && (repeats & 1) != 0 ? 0f : 1f);
            if (running && generation == before) finish(false);
        }

        void cancel() { if (running) finish(true); }

        private void finish(boolean cancelled) {
            stop();
            generation++;
            Listener end = listener;
            if (end != null) {
                if (cancelled) end.onAnimationCancel(this);
                end.onAnimationEnd(this);
            }
        }
    }

    interface TimeListener { void onTime(Clock clock, long totalMs, long deltaMs); }

    Clock clock() { return new Clock(); }

    final class Clock extends Task {
        private long previous;
        private TimeListener listener;
        void setTimeListener(TimeListener next) { listener = next; }
        void start() { previous = -1L; begin(); }
        void end() { stop(); }
        @Override void tick(long time) {
            long total = Math.max(0L, (time - start) / 1_000_000L);
            long delta = previous < 0L ? 0L : Math.max(0L, total - previous);
            previous = total;
            if (listener != null) listener.onTime(this, total, delta);
        }
    }

    Properties animate(View view) { return new Properties(view); }

    void cancel(View view) {
        ArrayList<Properties> running = properties.get(view);
        if (running == null) return;
        while (!running.isEmpty()) running.get(running.size() - 1).value.cancel();
    }

    /** Only running bundles retain a View. Delay, curve and end action never leak into its reuse. */
    public final class Properties {
        private final View view;
        private final float[] target = new float[7];
        private final float[] from = new float[7];
        private final float[] frame = new float[28];
        private int mask;
        private long duration = 300L;
        private long delay;
        private TimeInterpolator curve = DEFAULT;
        private Runnable endAction;
        private android.animation.Animator.AnimatorListener listener;
        private android.animation.ValueAnimator signal;
        private Value value;

        private Defaults config;
        Properties(View view) {
            this.view = view;
            if (retainPropertyDefaults) {
                config = defaults.computeIfAbsent(view, key -> new Defaults());
                duration = config.duration; delay = config.delay; curve = config.curve;
            }
        }
        private Properties to(int slot, float to) { target[slot] = to; mask |= 1 << slot; return this; }
        public Properties alpha(float to) { return to(0, to); }
        public Properties translationX(float to) { return to(1, to); }
        public Properties translationY(float to) { return to(2, to); }
        public Properties translationZ(float to) { return to(3, to); }
        public Properties scaleX(float to) { return to(4, to); }
        public Properties scaleY(float to) { return to(5, to); }
        public Properties rotation(float to) { return to(6, to); }
        public Properties setDuration(long ms) { duration = ms; if (config != null) config.duration = ms; return this; }
        public Properties setStartDelay(long ms) { delay = ms; if (config != null) config.delay = ms; return this; }
        public Properties setInterpolator(TimeInterpolator next) { curve = next; if (config != null) config.curve = next; return this; }
        public Properties setListener(android.animation.Animator.AnimatorListener next) {
            listener = next; return this;
        }
        public Properties withEndAction(Runnable action) { endAction = action; return this; }

        public void cancel() {
            OnlineMotion.this.cancel(view);
        }

        public void start() {
            if (mask == 0) return;
            ArrayList<Properties> running = properties.get(view);
            if (running != null) {
                for (int i = running.size() - 1; i >= 0; i--) {
                    Properties old = running.get(i);
                    old.mask &= ~mask;
                    if (old.mask == 0) old.value.cancel();
                }
            }
            from[0] = view.getAlpha();
            from[1] = view.getTranslationX();
            from[2] = view.getTranslationY();
            from[3] = view.getTranslationZ();
            from[4] = view.getScaleX();
            from[5] = view.getScaleY();
            from[6] = view.getRotation();
            value = value(0f, 1f);
            value.setDuration(duration);
            value.setStartDelay(delay);
            value.setInterpolator(curve);
            value.addUpdateListener(a -> {
                float t = a.getAnimatedValue();
                for (int i = 0; i < 7; i++) {
                    frame[i * 4] = from[i]; frame[i * 4 + 1] = target[i]; frame[i * 4 + 2] = t;
                }
                com.lab.hv.util.NativeKernels.motionBatch(frame, 7);
                for (int i = 0; i < 7; i++) {
                    if ((mask & (1 << i)) == 0) continue;
                    float v = frame[i * 4 + 3];
                    switch (i) {
                        case 0: view.setAlpha(v); break;
                        case 1: view.setTranslationX(v); break;
                        case 2: view.setTranslationY(v); break;
                        case 3: view.setTranslationZ(v); break;
                        case 4: view.setScaleX(v); break;
                        case 5: view.setScaleY(v); break;
                        case 6: view.setRotation(v); break;
                    }
                }
            });
            value.addListener(new Listener() {
                boolean cancelled;
                @Override public void onAnimationCancel(Value animation) {
                    cancelled = true;
                    if (listener != null) listener.onAnimationCancel(signal);
                }
                @Override public void onAnimationEnd(Value animation) {
                    ArrayList<Properties> list = properties.get(view);
                    if (list != null) {
                        list.remove(Properties.this);
                        if (list.isEmpty()) properties.remove(view);
                    }
                    if (listener != null) listener.onAnimationEnd(signal);
                    if (!cancelled && endAction != null) endAction.run();
                }
            });
            properties.computeIfAbsent(view, key -> new ArrayList<>()).add(this);
            if (listener != null) {
                signal = new android.animation.ValueAnimator();
                listener.onAnimationStart(signal);
            }
            value.start();
        }
    }
}
