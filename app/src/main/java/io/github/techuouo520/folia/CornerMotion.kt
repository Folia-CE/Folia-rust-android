package io.github.techuouo520.folia

import kotlin.math.*

internal const val CORNER_BUTTON_DIAMETER = 50f
internal const val CORNER_BUTTON_RADIUS = CORNER_BUTTON_DIAMETER / 2f

internal data class CornerRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * .5f
    val centerY get() = (top + bottom) * .5f
}

internal data class CornerContent(val x: Float, val y: Float, val scale: Float, val alpha: Float)
internal fun cornerSmooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Kotlin port of RealtimeFrostedGlassView's actual default bubble motion, including
 * animateBubbleTo, DropSway, stepBubbleStretch/applyBubbleDrop, content fling,
 * motionBlurShare and live-size spring. Coordinates are dp (the source's px / density).
 * Liquid optics are deliberately separate: this class only supplies motion/geometry.
 */
internal class CornerMotion {
    var x = 0f; private set
    var y = 0f; private set
    var vx = 0f; private set
    var vy = 0f; private set
    var blur = 0f; private set
    val progress get() = ((x + y) * .5f).coerceIn(0f, 1f)
    val docked get() = !opening && !morphing
    private var opening = false
    private var morphing = false
    private var blurStart = 0f
    private var blurCarry = 0f
    private var stretchX = 0f
    private var stretchY = 0f
    private var stretchVX = 0f
    private var stretchVY = 0f
    private val sway = CornerSway(1.2f)
    val press = CornerPressMotion()
    private var width = CORNER_BUTTON_DIAMETER
    private var height = CORNER_BUTTON_DIAMETER
    private var wantedHeight = CORNER_BUTTON_DIAMETER
    private var resizeVelocity = 0f
    private var resizeProgress = 1f
    private var resizeFromHeight = CORNER_BUTTON_DIAMETER
    private var resizing = false
    private var measured = false
    private var rowCount = 0
    private var appearFrom = Int.MAX_VALUE
    private var right = false
    private var dock = CornerRect(0f,0f,CORNER_BUTTON_DIAMETER,CORNER_BUTTON_DIAMETER)

    /** Live resting outline from the same bubble motion; changing it never restarts the window. */
    fun dockBounds(rect: CornerRect) { dock=rect }

    fun bounds(width: Float, height: Float?, right: Boolean): Boolean {
        var changed=false
        this.width = width.coerceAtLeast(CORNER_BUTTON_DIAMETER)
        this.right = right
        if (height != null && (opening || docked)) {
            val next=height.coerceAtLeast(CORNER_BUTTON_DIAMETER)
            if (!measured || docked) {
                this.height=next;wantedHeight=next;resizeVelocity=0f;resizeProgress=1f;resizing=false;measured=true
            } else if (next != wantedHeight) {
                resizeFromHeight=this.height;wantedHeight=next
                resizeProgress=0f;resizeVelocity=0f;resizing=true;changed=true
            }
        }
        return changed
    }

    fun rows(count: Int) {
        if(count!=rowCount) {
            appearFrom=if(opening && count>rowCount) rowCount else Int.MAX_VALUE
            rowCount=count
        }
    }
    fun rowAppearance(index: Int): Float = if(resizing && index>=appearFrom) cornerSmooth((resizeProgress-.3f)/.6f) else 1f

    /** A new intent turns the running motion, carrying position, stretch, sway and blur. */
    fun target(open: Boolean) {
        if (opening == open) return
        opening = open
        if (!open && resizing) {
            // The close starts from the live expanded shape; no resize runs underneath it.
            wantedHeight=height;resizing=false;resizeVelocity=0f;resizeProgress=1f
        }
        morphing = true
        val kick = if (open) 1.4f else -1.4f
        vx += kick; vy += kick
        blurStart = progress
        blurCarry = blur
    }

    fun step(open: Boolean, seconds: Float, pressed: Boolean = false): Boolean {
        target(open)
        val dt = seconds.coerceIn(0f, .048f)
        val pressMoving = press.step(pressed, dt)
        if (dt <= 0f) return morphing || pressMoving || height != wantedHeight
        val steps = max(1, ceil(dt / .004f).toInt())
        val h = dt / steps
        val t = if (opening) 1f else 0f
        if (resizing) {
            val omega = (2 * PI / .34).toFloat()
            repeat(steps) {
                resizeVelocity += (-omega * omega * (resizeProgress - 1) - 2 * .62f * omega * resizeVelocity) * h
                resizeProgress += resizeVelocity * h
            }
            height=mix(resizeFromHeight,wantedHeight,resizeProgress)
        }
        if (morphing) {
            val wx = (2 * PI / if (opening) .46 else .40).toFloat()
            val wy = (2 * PI / if (opening) .50 else .36).toFloat()
            val damping = if (opening) .655f else .695f
            repeat(steps) {
                vx += (-wx * wx * (x - t) - 2 * damping * wx * vx) * h; x += vx * h
                vy += (-wy * wy * (y - t) - 2 * damping * wy * vy) * h; y += vy * h
            }
        }
        if (morphing || resizing || !stretchAtRest()) {
            val tx = .1f * tanh(abs(vx * (width - CORNER_BUTTON_DIAMETER)) / 1400f)
            val ty = .1f * tanh(abs(if(morphing) vy * (height - dock.height) else resizeVelocity * (height-resizeFromHeight)) / 1400f)
            repeat(steps) {
                stretchVX += (24f * 24f * (tx - stretchX) - 2 * .92f * 24f * stretchVX) * h
                stretchX += stretchVX * h
                stretchVY += (24f * 24f * (ty - stretchY) - 2 * .92f * 24f * stretchVY) * h
                stretchY += stretchVY * h
            }
        }
        if (morphing) {
            val wayX = (width - CORNER_BUTTON_DIAMETER) * .5f * if (right) -1 else 1
            val wayY = (height - dock.height) * .5f
            val travelSign = if (opening) 1f else -1f
            sway.step(vx * wayX, vy * wayY, sign(wayX * travelSign), sign(wayY * travelSign), dt)
            blur = motionBlur(blurStart, (x + y) * .5f, t, blurCarry)
            if (abs(x - t) < .0015f && abs(y - t) < .0015f && abs(vx) < .02f && abs(vy) < .02f
                && stretchAtRest() && sway.atRest()) {
                x = t; y = t; vx = 0f; vy = 0f; blur = 0f
                stretchX = 0f; stretchY = 0f; stretchVX = 0f; stretchVY = 0f
                sway.reset(); morphing = false
            }
        }
        if(resizing && abs(resizeProgress-1)<.0015f && abs(resizeVelocity)<.02f && (morphing || stretchAtRest())) {
            height=wantedHeight;resizeProgress=1f;resizeVelocity=0f;resizing=false;appearFrom=Int.MAX_VALUE
            if(!morphing) {stretchX=0f;stretchY=0f;stretchVX=0f;stretchVY=0f}
        }
        return morphing || pressMoving || resizing || !stretchAtRest()
    }

    private fun stretchAtRest() = abs(stretchX) < .0015f && abs(stretchY) < .0015f && abs(stretchVX) < .02f && abs(stretchVY) < .02f

    /** Real changing outline. Never scale the window, its text, or its backdrop as a whole. */
    fun rect(): CornerRect {
        val cx = if (right) width - CORNER_BUTTON_RADIUS else CORNER_BUTTON_RADIUS
        val from = CornerRect(
            cx + (dock.left - CORNER_BUTTON_RADIUS) * press.scaleX,
            CORNER_BUTTON_RADIUS + (dock.top - CORNER_BUTTON_RADIUS) * press.scaleY,
            cx + (dock.right - CORNER_BUTTON_RADIUS) * press.scaleX,
            CORNER_BUTTON_RADIUS + (dock.bottom - CORNER_BUTTON_RADIUS) * press.scaleY,
        )
        val px = x.coerceIn(0f, 1.12f); val py = y.coerceIn(0f, 1.12f)
        val l = mix(from.left, 0f, px); val r = mix(from.right, width, px)
        val top = mix(from.top, 0f, py); val b = mix(from.bottom, height, py)
        val w = r - l; val h = b - top
        val dw = (w * ((1 + stretchX) * (1 + stretchY).pow(-.6f) - 1)).coerceIn(-22f, 22f)
        val dh = (h * ((1 + stretchY) * (1 + stretchX).pow(-.6f) - 1)).coerceIn(-22f, 22f)
        // Source's anchored geometry mirrored vertically for top-corner origins.
        return CornerRect(l + (if (right) -dw else 0f) + sway.left, top + sway.top,
            r + (if (right) 0f else dw) + sway.right, b + dh + sway.bottom)
    }

    fun radius(): Float = min(mix(CORNER_BUTTON_RADIUS, 26f, min(x, y).coerceIn(0f, 1.12f)), min(rect().width, rect().height) * .5f)
    fun iconAlpha(): Float = cornerSmooth(1 - progress * 1.55f)
    fun iconScale(): Float = mix(.6f, 1f, iconAlpha())

    fun content(centerX: Float, centerY: Float): CornerContent {
        if (!morphing && progress >= .999f) return CornerContent(0f, 0f, 1f, 1f)
        val r = rect()
        val ax = if (right) r.right - CORNER_BUTTON_RADIUS else r.left + CORNER_BUTTON_RADIUS
        val ay = r.top + CORNER_BUTTON_RADIUS
        val dx = centerX - ax; val dy = centerY - ay
        val order = (hypot(dx, dy) / max(1f, hypot(width, height))).coerceIn(0f, 1f)
        val lead = .42f * order
        val fx = max(0f, (x - lead) / (1 - lead)); val fy = max(0f, (y - lead) / (1 - lead))
        return CornerContent(-dx * (1 - fx) * .68f, -dy * (1 - fy) * .68f,
            mix(.78f, 1f, (fx + fy) * .5f).coerceIn(.78f, 1.05f), cornerSmooth((min(fx, fy) - .04f) / .5f))
    }

    companion object {
        fun motionBlur(start: Float, now: Float, target: Float, carry: Float): Float {
            val span = target - start
            val p = if (abs(span) < .0001f) 1f else (now - start) / span
            return max(cornerSmooth(p / .16f) * (1 - cornerSmooth((p - .5f) / .52f)), carry * (1 - cornerSmooth(p / .32f))).coerceIn(0f, 1f)
        }
    }
}

/** All four independently sprung edges from RealtimeFrostedGlassView.DropSway. */
private class CornerSway(private val intensity: Float) {
    private val positions = FloatArray(4)
    private val speeds = FloatArray(4)
    val left get() = positions[0]
    val top get() = positions[1]
    val right get() = positions[2]
    val bottom get() = positions[3]
    fun step(vx: Float, vy: Float, dx: Float, dy: Float, dt: Float) {
        val speed = hypot(vx, vy)
        val reach = 14.4f * intensity * tanh(speed / 1400f)
        val tx = if (speed > .001f) reach * vx / speed else 0f
        val ty = if (speed > .001f) reach * vy / speed else 0f
        val n = max(1, ceil(dt / .004f).toInt()); val h = dt / n
        repeat(n) {
            for (i in 0..3) {
                val dir = if (i % 2 == 0) dx else dy
                val leading = if (i < 2) dir < 0 else dir > 0
                val trailing = if (i < 2) dir > 0 else dir < 0
                val response = if (leading) .36 else if (trailing) .58 else .46
                val damping = if (leading) .585f else if (trailing) .48f else .54f
                val omega = (2 * PI / response).toFloat()
                speeds[i] += (omega * omega * ((if (i % 2 == 0) tx else ty) - positions[i]) - 2 * damping * omega * speeds[i]) * h
                positions[i] += speeds[i] * h
            }
        }
    }
    fun atRest() = positions.all { abs(it) < .25f } && speeds.all { abs(it) < 4f }
    fun reset() { positions.fill(0f); speeds.fill(0f) }
}

/** NowPlayingQuickButtons.Button.step + LiquidDropWobble, without liquid optics. */
internal class CornerPressMotion {
    private var press = 0f
    private var lastHalf = CORNER_BUTTON_RADIUS
    private var stretch = 0f
    private var velocity = 0f
    private var target = 0f
    private var direction = 1f
    private var lastSpeed = 0f
    private var acceleration = 0f
    private val swell get() = 1 + .08f * cornerSmooth(press)
    val scaleX get() = swell * (1 + stretch)
    val scaleY get() = swell * (1 + stretch).pow(-.6f)
    fun step(pressed: Boolean, seconds: Float): Boolean {
        if (seconds <= 0) return pressed || press != 0f || !rest()
        val wanted = if (pressed) 1f else 0f
        val rate = if (pressed) 1 / .11f else 1 / .24f
        press = if (wanted > press) min(wanted, press + seconds * rate) else max(wanted, press - seconds * rate)
        val half = CORNER_BUTTON_RADIUS * swell
        val speed = (half - lastHalf) / seconds; lastHalf = half
        val dt = seconds.coerceAtMost(1 / 30f)
        if (abs(speed) > 24) direction = sign(speed)
        val raw = (speed - lastSpeed) / dt; lastSpeed = speed
        acceleration += (raw - acceleration) * (1 - exp(-dt / .025f))
        target = .14f * tanh(abs(speed) / 900f) + .1f * tanh(acceleration * direction / 5000f)
        val n = max(1, ceil(dt / (1 / 240f)).toInt()); val h = dt / n
        repeat(n) {
            velocity += (19.6f * 19.6f * (target - stretch) - 2 * .42f * 19.6f * velocity) * h
            stretch += velocity * h
            if (stretch < -.16f) { stretch = -.16f; if (velocity < 0) velocity = 0f }
            if (stretch > .32f) { stretch = .32f; if (velocity > 0) velocity = 0f }
        }
        if (press == wanted && rest()) {
            stretch = 0f; velocity = 0f; target = 0f
            return false
        }
        return true
    }
    private fun rest() = abs(target) < .0015f && abs(stretch) < .0015f && abs(velocity) < .02f
}
