package io.github.techuouo520.folia

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Null means keep the current target, including while the ball is in flight. */
internal fun dockSideForTouch(x: Float, gyroLeft: Boolean? = null): Boolean? = when {
    x !in 0f..1f -> null
    x < .40f -> true
    x > .60f -> false
    x < .45f && gyroLeft == true -> true
    x > .55f && gyroLeft == false -> false
    else -> null
}

/** Screen coordinates: +X right, +Y up, +Z out of the display. All timestamps are sensor nanoseconds. */
internal class GripEstimator {
    private var last = 0L
    private var quietUntil = 0L
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private val previousRate = FloatArray(3)
    private val angularAccel = FloatArray(3)
    private var linearY = 0f
    private var linearZ = 0f
    private val window = PivotWindow()
    private var evidence: PivotEvidence? = null

    var touchEligible = false; private set

    fun reset() {
        touchEligible = false
        last = 0L
        evidence = null
        window.clear()
    }

    /** Estimate a physical pivot only. The UI applies the independent live finger-contact gate. */
    fun sample(t: Long, ax: Float, ay: Float, az: Float, wx: Float, wy: Float, wz: Float): PivotEvidence? {
        val dt = (t - last) * 1e-9f
        if (last == 0L || dt !in .001f.. .12f) {
            gx = ax; gy = ay; gz = az
            previousRate[0] = wx; previousRate[1] = wy; previousRate[2] = wz
            angularAccel.fill(0f)
            linearY = 0f; linearZ = 0f
            quietUntil = t + 450_000_000L
            reset()
            last = t
            return null
        }
        last = t
        // Gravity is fixed in the world. Transport it into the new device frame before subtracting it.
        val px = gx + (wz * gy - wy * gz) * dt
        val py = gy + (wx * gz - wz * gx) * dt
        val pz = gz + (wy * gx - wx * gy) * dt
        val dx = ax - px; val dy = ay - py; val dz = az - pz
        val gravityGain = 1f - exp(-dt / .8f)
        gx = px + gravityGain * dx; gy = py + gravityGain * dy; gz = pz + gravityGain * dz
        val filter = 1f - exp(-dt / .045f)
        fun updateAxis(i: Int, rate: Float) {
            angularAccel[i] += filter * ((rate - previousRate[i]) / dt - angularAccel[i])
            previousRate[i] = rate
        }
        updateAxis(0, wx); updateAxis(1, wy); updateAxis(2, wz)
        linearY += filter * (dy - linearY)
        linearZ += filter * (dz - linearZ)

        val gravitySquared = gx * gx + gy * gy + gz * gz
        val flat = gz * gz > .93f * gravitySquared
        val impact = dx * dx + dy * dy + dz * dz > 2.7f * 2.7f || wx * wx + wy * wy + wz * wz > 6.25f
        if (flat || impact || gravitySquared !in 78f..117f) quietUntil = t + 450_000_000L
        touchEligible = t >= quietUntil
        if (!touchEligible) {
            window.clear(); evidence = null
            return null
        }
        window.add(dt, angularAccel[0], angularAccel[2], -angularAccel[1], linearY, linearZ)
        if (window.time >= .72f) {
            val arm = window.leverArm()
            val previous = evidence
            evidence = arm?.let {
                val count = if (previous != null && previous.arm * it > 0) minOf(previous.windows + 1, 3) else 1
                PivotEvidence(it, count, t)
            }
            window.clear()
        }
        return evidence
    }

    /** For a lateral lever arm r, a = alpha × r gives (ay, az) = rx * (alphaZ, -alphaY).
     * Fit the signed distance after removing constant offsets. Translation or mixed-axis motion
     * must explain little of the residual before a window can vote for either hand. */
    private class PivotWindow {
        var time = 0f
        private var sy = 0f; private var sz = 0f
        private var by = 0f; private var bz = 0f
        private var angularPower = 0f; private var linearPower = 0f
        private var cross = 0f; private var pitchPower = 0f

        fun clear() {
            time = 0f; sy = 0f; sz = 0f; by = 0f; bz = 0f
            angularPower = 0f; linearPower = 0f; cross = 0f; pitchPower = 0f
        }
        fun add(dt: Float, pitch: Float, ry: Float, rz: Float, ay: Float, az: Float) {
            time += dt
            sy += dt * ry; sz += dt * rz; by += dt * ay; bz += dt * az
            angularPower += dt * (ry * ry + rz * rz)
            linearPower += dt * (ay * ay + az * az)
            cross += dt * (ry * ay + rz * az)
            pitchPower += dt * pitch * pitch
        }
        fun leverArm(): Float? {
            val omega = angularPower - (sy * sy + sz * sz) / time
            val accel = linearPower - (by * by + bz * bz) / time
            if (omega < 2.25f * time || accel < .0064f * time || pitchPower > .55f * omega) return null
            val covariance = cross - (sy * by + sz * bz) / time
            val radius = covariance / omega
            if (abs(radius) !in .018f.. .18f || abs(covariance) < .82f * sqrt(omega * accel)) return null
            return radius
        }
    }
}

/** The touch region can ask for one more IMU window; it can never supply or reverse a side. */
internal data class PivotEvidence(val arm: Float, val windows: Int, val measuredAt: Long) {
    fun handDuringContact(touchX: Float?, now: Long): Boolean? {
        if (touchX == null || touchX !in 0f..1f || now - measuredAt !in 0L..800_000_000L) return null
        val left = arm > 0f
        val reachingAcross = if (left) touchX > .65f else touchX < .35f
        return left.takeIf { windows >= (if (reachingAcross) 3 else 2) }
    }
}

/** Short, touch-locked angular response. Positive screen-Y rotation from a normal tap
 * places the support to the left of the contact, negative to its right. Near the far
 * edge that inequality alone cannot identify a hand, so leave it to the pivot fit. */
internal class TouchRoll {
    private val times = LongArray(64)
    private val rates = FloatArray(times.size)
    private var next = 0
    private var count = 0
    private var previousVote: Boolean? = null
    private var lastVoteAt = 0L

    @Synchronized fun clear() {
        count = 0
        previousVote = null
        lastVoteAt = 0L
    }

    @Synchronized fun record(t: Long, yRate: Float) {
        times[next] = t
        rates[next] = yRate
        next = (next + 1) % times.size
        count = minOf(count + 1, times.size)
    }

    @Synchronized fun hand(down: Long, end: Long, x: Float, finished: Boolean): Boolean? {
        if (count < 5 || end < down || down == lastVoteAt) return null
        var baseline = 0f
        var minRate = Float.POSITIVE_INFINITY
        var maxRate = Float.NEGATIVE_INFINITY
        var before = 0
        var baselineStart = 0L
        var baselineEnd = 0L
        var first = 0L
        var last = 0L
        var angle = 0f
        var path = 0f
        var previous = 0f
        var weak: Boolean? = null
        for (i in 0 until count) {
            val at = (next - count + times.size + i) % times.size
            val t = times[at]
            if (t < down - 160_000_000L) continue
            if (t > end || t > down + 130_000_000L) break
            val rate = rates[at]
            if (t <= down - 20_000_000L) {
                if (before == 0) baselineStart = t
                baselineEnd = t
                baseline += rate
                minRate = minOf(minRate, rate)
                maxRate = maxOf(maxRate, rate)
                before++
            } else if (t >= down) {
                if (before < 3 || baselineEnd - baselineStart < 40_000_000L || down - baselineEnd > 60_000_000L) return null
                val delta = rate - baseline / before
                if (first == 0L) {
                    if (t - down > 35_000_000L) return null
                    first = t
                } else {
                    if (t - last > 40_000_000L) return null
                    val dt = (t - last) * 1e-9f
                    angle += .5f * (previous + delta) * dt
                    path += .5f * (abs(previous) + abs(delta)) * dt
                }
                previous = delta
                last = t
                val span = (t - first) * 1e-9f
                if (span < .025f) continue
                // Require a coherent excursion above this contact's own pre-touch noise.
                val noise = maxOf(.018f, maxRate - minRate)
                if (abs(angle) < maxOf(.0018f, 2f * noise * span) || abs(angle) < .84f * path) continue
                val left = angle > 0f
                if ((left && x > .63f) || (!left && x < .37f) || (weak != null && weak != left)) return null
                val corroborated = previousVote == left && down - lastVoteAt < 3_000_000_000L
                val strong = abs(angle) >= maxOf(.004f, 3f * noise * span)
                if (strong || corroborated) {
                    previousVote = left
                    lastVoteAt = down
                    return left
                }
                weak = left
            }
        }
        if (weak != null && (finished || end >= down + 130_000_000L)) {
            previousVote = weak
            lastVoteAt = down
        }
        return null
    }
}

internal data class GripSample(val t: Long, val x: Float, val y: Float, val z: Float)

/** Delivery order is independent across sensors. Pair in sensor time, interpolating
 * acceleration at each gyro sample so a burst from one sensor cannot erase the fit. */
internal class GripSamples(private val paired: (GripSample, Float, Float, Float) -> Unit) {
    private val acc = ArrayDeque<GripSample>()
    private val gyro = ArrayDeque<GripSample>()

    fun clear() { acc.clear(); gyro.clear() }

    fun add(isAccel: Boolean, sample: GripSample) {
        val queue = if (isAccel) acc else gyro
        if (queue.isNotEmpty() && sample.t <= queue.last().t) return
        if (queue.size == 64) queue.removeFirst()
        queue.addLast(sample)
        while (gyro.isNotEmpty() && acc.isNotEmpty()) {
            val g = gyro.first()
            while (acc.size > 1 && acc[1].t <= g.t) acc.removeFirst()
            val a = acc.first()
            if (g.t < a.t) { gyro.removeFirst(); continue }
            if (g.t == a.t) {
                paired(g, a.x, a.y, a.z)
            } else {
                if (acc.size == 1) break
                val b = acc[1]
                if (b.t - a.t <= 100_000_000L) {
                    val f = (g.t - a.t).toFloat() / (b.t - a.t)
                    paired(g, a.x + f*(b.x-a.x), a.y + f*(b.y-a.y), a.z + f*(b.z-a.z))
                }
            }
            gyro.removeFirst()
        }
    }
}
