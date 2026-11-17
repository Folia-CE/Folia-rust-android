package io.github.techuouo520.folia

import kotlin.math.abs

/** BezierControl presets used by NowPlayingBarView. The coefficients are ordinates, not x/y pairs. */
internal class PlayerBezier(private vararg val points: Double) {
    private val work = DoubleArray(points.size)

    fun sample(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f).toDouble()
        points.copyInto(work)
        for (last in work.lastIndex downTo 1) {
            for (i in 0 until last) work[i] = (1 - t) * work[i] + t * work[i + 1]
        }
        return work[0].toFloat()
    }

    val startSlope = ((points[1] - points[0]) * points.lastIndex).toFloat()
}

// These curves are sampled on the UI frame clock.
internal val playerAttack = PlayerBezier(0.0, .0041, .8, .82, .86, .9, .98, .982, .985, .987, .992, .996, .999, 1.0)
internal val playerPress = PlayerBezier(0.0, .09, .18, .27, .36, .45, .54, .63, .72, .76, .8, .84, .88, .92, .95, .955, .958, .97, .985, .996, .999, 1.0)

internal fun settleMusicBar(start: Float, progress: Float, travelDp: Float): Boolean =
    if (start < .5f) travelDp <= -72f || progress >= .5f else travelDp < 72f && progress > .5f

internal class MusicBarMotion {
    var value = 0f
        private set
    var velocity = 0f
        private set
    private val duration = .65f
    private var from = 0f
    private var to = 0f
    private var elapsed = duration
    private var tangent = 0f
    val moving get() = elapsed < duration

    fun target(open: Boolean) {
        val next = if (open) 1f else 0f
        if (to == next) return
        from = value
        to = next
        elapsed = 0f
        tangent = if (velocity == 0f && (from == 0f || from == 1f)) 0f
            else velocity * duration - playerAttack.startSlope * (to - from)
    }

    fun hold() {
        elapsed = duration
        to = value
    }

    fun drag(progress: Float, dragVelocity: Float = 0f) {
        value = progress.coerceIn(0f, 1f)
        velocity = if (value == progress && value > 0f && value < 1f) dragVelocity else 0f
        hold()
    }

    private fun sample(t: Float): Float {
        val fraction = playerAttack.sample(t)
        if (tangent == 0f) return playerMix(from, to, fraction)
        // Continue along the incoming tangent, within the space left on that side. Advancing
        // the starting point before blending to the target avoids hitting a clamp or rebounding
        // on fast same-direction releases. At t=0 the correction's derivative is tangent.
        val room = if (tangent > 0f) 1f - from else from
        val displacement = tangent * t
        val carried = if (room > 0f) room * displacement / (room + abs(displacement)) else 0f
        return playerMix(from + carried, to, fraction)
    }

    fun step(seconds: Float): Boolean {
        if (!moving) return false
        elapsed = (elapsed + seconds.coerceAtLeast(0f)).coerceAtMost(duration)
        if (!moving) {
            value = to
            velocity = 0f
            return false
        }
        val t = elapsed / duration
        value = sample(t)
        val a = (t - .0001f).coerceAtLeast(0f)
        val b = (t + .0001f).coerceAtMost(1f)
        velocity = (sample(b) - sample(a)) / ((b - a) * duration)
        return true
    }
}
