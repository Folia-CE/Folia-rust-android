package io.github.techuouo520.folia.spatial

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** SalamPlayer's one-shot nanosecond alarm. It posts demand, never calculates animation. */
internal class DailySpatialFramePacer(
    private val dispatch: (Runnable) -> Unit,
    private val callback: () -> Unit,
) : AutoCloseable {
    companion object { const val RHINE_INTERVAL_NS = 11_111_112L }
    private var timer: ScheduledThreadPoolExecutor? = null
    private var alarm: ScheduledFuture<*>? = null
    private var generation = 0L
    private var lastStart = 0L
    private var started = false
    private var closed = false

    fun remaining(now: Long, rhine: Boolean): Long =
        if (rhine && started) (RHINE_INTERVAL_NS - (now - lastStart)).coerceAtLeast(0L) else 0L

    fun started(now: Long, rhine: Boolean) { lastStart = now; started = rhine }

    fun schedule(delay: Long) {
        cancel()
        if (closed) return
        val executor = timer ?: ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "Folia-RhineFrame").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setKeepAliveTime(1, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }.also { timer = it }
        val token = generation
        alarm = executor.schedule({
            dispatch(Runnable {
                if (!closed && token == generation) { alarm = null; callback() }
            })
        }, delay, TimeUnit.NANOSECONDS)
    }

    fun cancel() { generation++; alarm?.cancel(false); alarm = null }
    fun reset() { cancel(); started = false }
    override fun close() { reset(); closed = true; timer?.shutdownNow(); timer = null }
}
