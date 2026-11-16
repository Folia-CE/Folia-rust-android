package io.github.techuouo520.folia.spatial

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Host regression: exact pacing and stale alarms delivered after cancel/restart. */
fun main() {
    val dispatched = LinkedBlockingQueue<Runnable>()
    var calls = 0
    val pacer = DailySpatialFramePacer({ dispatched.add(it) }) { calls++ }
    try {
        check(pacer.remaining(0, true) == 0L)
        pacer.started(1_000_000_000L, true)
        check(pacer.remaining(1_010_000_000L, true) == 1_111_112L)
        check(pacer.remaining(1_011_111_111L, true) == 1L)
        check(pacer.remaining(1_011_111_112L, true) == 0L)
        check(pacer.remaining(1_000_000_000L, false) == 0L)

        fun alarm(): Runnable {
            pacer.schedule(0)
            return checkNotNull(dispatched.poll(3, TimeUnit.SECONDS)) { "Alarm was not dispatched" }
        }
        val cancelled = alarm()
        pacer.reset()
        cancelled.run()
        check(calls == 0)
        check(pacer.remaining(1_000_000_001L, true) == 0L)

        val superseded = alarm()
        val current = alarm()
        superseded.run()
        check(calls == 0)
        current.run()
        check(calls == 1)

        val closed = alarm()
        pacer.close()
        closed.run()
        check(calls == 1)
        println("Frame pacer: nanosecond deadline, idle reset, superseded/cancelled/closed alarms passed")
    } finally { pacer.close() }
}
