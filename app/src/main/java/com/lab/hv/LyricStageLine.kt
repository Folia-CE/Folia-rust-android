package com.lab.hv

class LyricStageLine(
    @JvmField val timeMs: Long,
    @JvmField val text: String,
    @JvmField val tokenTimeMs: LongArray?,
    @JvmField val tokenStartCodePoint: IntArray?,
    @JvmField val tokenEndCodePoint: IntArray?,
    @JvmField val terminalEndMs: Long,
) {
    /** Same words at the same times, word timing included. */
    fun sameAs(other: LyricStageLine): Boolean =
        timeMs == other.timeMs && terminalEndMs == other.terminalEndMs && text == other.text
                && tokenTimeMs.contentEquals(other.tokenTimeMs)
                && tokenStartCodePoint.contentEquals(other.tokenStartCodePoint)
                && tokenEndCodePoint.contentEquals(other.tokenEndCodePoint)
}

