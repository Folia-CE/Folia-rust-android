package io.github.techuouo520.folia

import kotlin.math.*

/** Reproducible IMU fixtures, independent of the estimator's gravity compensation and spring. */
internal fun checkGripDock() {
    for (gyro in listOf(true, false, null)) {
        check(dockSideForTouch(.39f, gyro) == true && dockSideForTouch(.61f, gyro) == false)
        for (x in listOf(.45f, .5f, .55f)) check(dockSideForTouch(x, gyro) == null)
        for (x in listOf(.40f, .43f)) check(dockSideForTouch(x, gyro) == true.takeIf { gyro == true })
        for (x in listOf(.57f, .60f)) check(dockSideForTouch(x, gyro) == false.takeIf { gyro == false })
    }
    checkContactResponse()
    val gravity = 9.80665
    fun replay(arm: Double, sideLying: Boolean = false, flat: Boolean = false,
               bumps: Boolean = false, rotate: Boolean = true): PivotEvidence? {
        val estimator = GripEstimator()
        var last: PivotEvidence? = null
        for (i in 0..500) {
            val t = i * .01
            val f = 2 * PI * 1.35
            val angle = if (rotate) .14 * (1 - cos(f*t)) else 0.0
            val speed = if (rotate) .14 * f * sin(f*t) else 0.0
            val alpha = if (rotate) .14 * f*f * cos(f*t) else 0.0
            // Rotate the actual gravity vector into body coordinates, then add the
            // centripetal and tangential acceleration at an IMU offset from the grip.
            val gx = if (sideLying) gravity else 0.0
            val gy = if (!sideLying && !flat) gravity else 0.0
            val gz = if (flat) gravity else 0.0
            val ax = gx*cos(angle) - gz*sin(angle) - speed*speed*arm
            val az = gx*sin(angle) + gz*cos(angle) - alpha*arm
            val shake = if (bumps) 4.5*cos(t*45) else 0.0
            last = estimator.sample(1_000_000_000L+i*10_000_000L,
                ax.toFloat(), (gy+shake).toFloat(), az.toFloat(), 0f,speed.toFloat(),0f)
            if (flat || bumps || !rotate) check(last == null) {"spurious pivot at $i"}
        }
        return last
    }
    for (left in listOf(true,false)) for (lying in listOf(true,false)) {
        val e = checkNotNull(replay(if (left) .052 else -.052, sideLying=lying))
        check(e.handDuringContact(null,e.measuredAt) == null)
        check(e.handDuringContact(.5f,e.measuredAt) == left)
        check(e.handDuringContact(.5f,e.measuredAt+1_000_000_000L) == null)
        val across = if (left) .9f else .1f
        check(e.copy(windows=2).handDuringContact(across,e.measuredAt) == null)
        check(e.handDuringContact(across,e.measuredAt) == left)
    }
    replay(.052, flat=true, rotate=false)
    replay(-.052, sideLying=true, rotate=false)
    replay(.052, bumps=true)
    replay(.052, flat=true, rotate=true)
    for (hz in listOf(60,90,120)) {
        val flight = DockFlight(false)
        val bubble = CornerMotion(bottomOrigin=true, flight=flight)
        bubble.bounds(360f,120f,true)
        repeat(3*hz) { frame ->
            val rect = bubble.rect(); val v = flight.velocity
            flight.targetLeft = frame / 3 % 2 == 0
            val open = frame / 5 % 2 == 1
            bubble.target(open)
            check(flight.velocity == v && bubble.rect() == rect)
            flight.step(1f/hz); bubble.step(open,1f/hz,frame % 7 == 0)
            check(flight.rightShare in 0f..1f && bubble.rect().width > 0f)
            val r = bubble.rect()
            check(bubble.anchorX(r,25f) in r.left..r.right)
        }
        for (left in listOf(true,false)) {
            flight.targetLeft = left
            repeat(hz*3) { flight.step(1f/hz); bubble.step(false,1f/hz) }
            val r = bubble.rect()
            check(bubble.docked && flight.velocity == 0f && abs(r.left - if (left) 0f else 310f) < .01f)
        }
    }
    println("PASS: synthetic grip/side-lying/flat/impact traces, contact gate, and simultaneous dock flight/morph reversals")
}

/** Two short taps, each preceded by a motionless grip. Feed the production timestamp
 * aligner slower acceleration in bursts; no sustained pivot evidence should exist. */
private fun checkContactResponse() {
    val down = 2_000_000_000L
    fun replay(rate: Float, x: Float, second: Boolean = false, flat: Boolean = false,
               bump: Boolean = false, lying: Boolean = false, held: Boolean = true,
               delayed: Boolean = false): Pair<Boolean?, Int> {
        val estimator = GripEstimator()
        val roll = TouchRoll()
        var result: Boolean? = null
        var latency = -1
        val pairs = GripSamples { g, ax, ay, az ->
            check(estimator.sample(g.t, ax, ay, az, g.x, g.y, g.z) == null)
            if (estimator.touchEligible) roll.record(g.t, g.y) else roll.clear()
            val elapsed = (g.t-down)/1_000_000L
            val age = if (elapsed >= 500) elapsed-500 else elapsed
            if (held && (elapsed < 500 || second) && age in 0..80 && result == null) {
                val start = if (elapsed >= 500) down+500_000_000L else down
                if (!delayed || age == 80L) {
                    result = roll.hand(start, g.t, x, age == 80L)
                    if (result != null) latency = age.toInt()
                }
            }
        }
        for (block in 0..43) {
            // 100 Hz gyro, 50 Hz accelerometer, groups of four callbacks arrive in different orders.
            for (j in 0..3) {
                val ms = block*40+j*10
                val age = if (ms >= 1500) ms-1500 else ms-1000
                val response = if (age in 10..70) rate else 0f
                pairs.add(false, GripSample(1_000_000_000L+ms*1_000_000L,0f,response,0f))
            }
            for (j in 0..1) {
                val ms=block*40+j*20
                val z = if (flat) 9.81f else if (bump && ms in 1000..1100) 5f else 0f
                pairs.add(true, GripSample(1_000_000_000L+ms*1_000_000L,
                    if (lying) 9.81f else 0f, if (flat || lying) 0f else 9.81f,z))
            }
        }
        return result to latency
    }
    for (left in listOf(true,false)) {
        val sign = if (left) 1f else -1f
        val x = if (left) .27f else .73f
        for (lying in listOf(true,false)) {
            val (hand, ms) = replay(sign*.13f,x,lying=lying)
            check(hand == left && ms in 25..80) { "short tap: hand=$hand latency=$ms" }
        }
        check(replay(sign*.13f,x,delayed=true).first == left)
        check(replay(sign*.04f,x).first == null)
        check(replay(sign*.04f,x,second=true).first == left)
        check(replay(sign*.13f,1f-x).first == null)
        check(replay(sign*.13f,x,bump=true).first == null)
        check(replay(sign*.13f,x,flat=true).first == null)
        check(replay(sign*.13f,x,held=false).first == null)
        check(replay(0f,x,second=true).first == null)
    }
    println("PASS: contact-locked short taps, weak-tap confirmation, async sensor delivery and rejection cases")
}
