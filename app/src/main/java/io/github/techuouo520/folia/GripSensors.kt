package io.github.techuouo520.folia

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.SystemClock
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Settings can overlap while one bubble is closing. Each owns its own pin. */
internal class GripGate {
    private val panels = mutableStateListOf<Any>()
    val pinned get() = panels.isNotEmpty()
    var excludedDown = Long.MIN_VALUE
    private var heldDown = Long.MIN_VALUE
    private var pending: (() -> Unit)? = null

    fun defer(down: Long) { heldDown = down; pending = null }
    fun resolve(down: Long, allow: Boolean) {
        if (!allow) excludedDown = down
        if (heldDown != down) return
        heldDown = Long.MIN_VALUE
        val decision = pending
        pending = null
        if (allow && !pinned && excludedDown != down) decision?.invoke()
    }
    fun offer(down: Long, decision: () -> Unit) {
        if (pinned || excludedDown >= down) return
        if (heldDown == down) pending = decision else decision()
    }
    fun pin(owner: Any) { panels.add(owner) }
    fun unpin(owner: Any) { panels.remove(owner) }
}
internal val LocalGripGate = staticCompositionLocalOf<GripGate?> { null }

@Composable
internal fun PinMusicBarSide(active: Boolean) {
    val gate = LocalGripGate.current
    if (active && gate != null) DisposableEffect(gate) {
        val owner = Any()
        gate.pin(owner)
        onDispose { gate.unpin(owner) }
    }
}

/** Main-thread gate shared by the home surface and foreground sensor collection. */
internal class GripContact {
    val roll = TouchRoll()
    private var x: Float? = null
    private var down = 0L
    private var up = 0L
    @Volatile var sampleUntil = 0L; private set
    private var evidence: PivotEvidence? = null
    private var onSide: ((Boolean) -> Unit)? = null

    fun begin(position: Float, t: Long, accept: (Boolean) -> Unit) {
        onSide = accept
        down = t; up = 0L; x = position
        sampleUntil = t + 180_000_000L
        decide()
    }

    fun release(t: Long) {
        up = t
        decide()
    }

    fun cancel() { x = null; onSide = null; sampleUntil = 0L }
    fun motion(value: PivotEvidence?) { evidence = value; decide() }
    fun clear() { evidence = null; roll.clear(); cancel() }

    private fun decide() {
        val position = x ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        // A batched IMU packet can arrive after UP. Only integrate samples from the
        // actual contact interval. A new DOWN or a cancelled gesture supersedes it.
        if (up != 0L && now - up > 100_000_000L) { cancel(); return }
        val pivot = if (up == 0L) evidence?.handDuringContact(position, now) else null
        val tap = if (pivot == null && now <= sampleUntil + 100_000_000L)
            roll.hand(down, if (up == 0L) now else up, position, up != 0L) else null
        (pivot ?: tap)?.let { hand ->
            dockSideForTouch(position, hand)?.let { onSide?.invoke(it) }
            cancel()
        }
    }
}

internal fun Modifier.observeMusicBarSide(dockLeft: MutableState<Boolean>, grip: GripContact, gate: GripGate) = pointerInput(dockLeft, grip, gate) {
    // Android input timestamps use uptime; SensorEvent timestamps include deep sleep.
    fun sensorTime(uptime: Long) = SystemClock.elapsedRealtimeNanos() -
        (SystemClock.uptimeMillis() - uptime) * 1_000_000L
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            grip.cancel()
            if (gate.pinned || gate.excludedDown == down.uptimeMillis) return@awaitEachGesture
            if (down.type == PointerType.Touch && size.width > 0 && currentEvent.changes.count { it.pressed } == 1) {
                val x = down.position.x / size.width
                val direct = dockSideForTouch(x)
                if (direct != null) {
                    gate.offer(down.uptimeMillis) { dockLeft.value = direct }
                    return@awaitEachGesture
                }
                // Only sample this contact if matching gyro evidence could actually change sides.
                val otherSide = !dockLeft.value
                if (dockSideForTouch(x, otherSide) != otherSide) return@awaitEachGesture
                grip.begin(x, sensorTime(down.uptimeMillis)) { hand -> gate.offer(down.uptimeMillis) { dockLeft.value = hand } }
                var released = false
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val finger = event.changes.singleOrNull() ?: break
                        if (finger.id != down.id || finger.position.x !in 0f..size.width.toFloat() ||
                            finger.position.y !in 0f..size.height.toFloat()) break
                        if (!finger.pressed) {
                            released = !finger.isConsumed
                            if (released) grip.release(sensorTime(finger.uptimeMillis))
                        }
                    } while (finger.pressed)
                } finally { if (!released) grip.cancel() }
            }
        }
    } finally { grip.cancel() }
}

@Composable
internal fun ObserveGripSensors(grip: GripContact) {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, view, grip) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val display = view.display ?: return@repeatOnLifecycle
            grip.clear()
            try { gripReadings(context, display, grip).collect(grip::motion) }
            finally { grip.clear() }
        }
    }
}

private fun gripReadings(context: Context, display: Display, grip: GripContact) = callbackFlow<PivotEvidence?> {
    val sensors = context.getSystemService(SensorManager::class.java)
    val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (sensors == null || gyro == null || accel == null) {
        Log.w("FoliaGrip", "Grip sensing unavailable (gyro=${gyro != null}, accelerometer=${accel != null})")
        close()
        return@callbackFlow
    }
    val displays = context.getSystemService(DisplayManager::class.java)
    val thread = HandlerThread("Folia grip sensors").apply { start() }
    val handler = Handler(thread.looper)
    val estimator = GripEstimator()
    var published: PivotEvidence? = null
    fun publish(value: PivotEvidence?) {
        if (published !== value) { published = value; trySend(value) }
    }
    var gyroReliable = false
    var accelReliable = false
    val pairs = GripSamples { g, ax, ay, az ->
        val result = estimator.sample(g.t, ax, ay, az, g.x, g.y, g.z).takeIf { gyroReliable && accelReliable }
        if (estimator.touchEligible) grip.roll.record(g.t, g.y) else grip.roll.clear()
        if (published !== result) publish(result)
        else if (g.t <= grip.sampleUntil) trySend(result)
    }
    fun invalidate() {
        pairs.clear(); estimator.reset(); grip.roll.clear(); publish(null)
    }
    var rotation = display.rotation
    val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) { if (id == display.displayId) close() }
        override fun onDisplayChanged(id: Int) {
            if (id == display.displayId && rotation != display.rotation) {
                rotation = display.rotation
                invalidate()
            }
        }
    }
    val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            val a = event.sensor.type == Sensor.TYPE_ACCELEROMETER
            val reliable = event.accuracy > SensorManager.SENSOR_STATUS_UNRELIABLE
            if (a) accelReliable = reliable else gyroReliable = reliable
            // Relative tap rotation subtracts the immediately preceding baseline. Absolute
            // calibration status gates the longer pivot fit, not every differential sample.
            val rawGyro = event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
            val v = event.values
            val x = if (rawGyro) v[0] - v[3] else v[0]
            val y = if (rawGyro) v[1] - v[4] else v[1]
            val z = if (rawGyro) v[2] - v[5] else v[2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) { invalidate(); return }
            val t = event.timestamp
            pairs.add(a, when (rotation) {
                Surface.ROTATION_90 -> GripSample(t, -y, x, z)
                Surface.ROTATION_180 -> GripSample(t, -x, -y, z)
                Surface.ROTATION_270 -> GripSample(t, y, -x, z)
                else -> GripSample(t, x, y, z)
            })
        }
    }
    try {
        displays?.registerDisplayListener(displayListener, handler)
        if (!sensors.registerListener(listener, accel, 10_000, handler) ||
            !sensors.registerListener(listener, gyro, 10_000, handler)) {
            Log.w("FoliaGrip", "Could not register ${gyro.name} and ${accel.name}")
            close()
        }
        awaitClose()
    } finally {
        sensors.unregisterListener(listener)
        displays?.unregisterDisplayListener(displayListener)
        thread.quitSafely()
    }
}
