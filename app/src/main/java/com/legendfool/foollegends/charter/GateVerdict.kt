package com.legendfool.foollegends.charter

/**
 * Outcome of a config-endpoint call.
 *
 * [answered] separates the two kinds of "no". A server that replies — 404, an empty
 * body, `ok:false`, anything — has ruled on this install, and that ruling is final
 * and worth writing down. A request that never reached one has ruled on nothing, and
 * the run must not be recorded as native on the strength of it.
 */
data class GateVerdict(
    val granted: Boolean,
    val landing: String?,
    val expiresAt: Long,
    val answered: Boolean
) {
    companion object {
        fun refused(answered: Boolean = true) = GateVerdict(false, null, 0L, answered)
        fun unreachable() = refused(answered = false)
        fun allowed(url: String, expiresAt: Long) = GateVerdict(true, url, expiresAt, true)
    }
}
