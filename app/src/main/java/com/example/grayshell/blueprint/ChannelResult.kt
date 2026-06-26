package com.example.grayshell.blueprint

data class ChannelResult(
    val active: Boolean,
    val destination: String?,
    val expiresAt: Long
) {
    companion object {
        fun native() = ChannelResult(false, null, 0L)
        fun stream(url: String, exp: Long = 0L) = ChannelResult(true, url, exp)
    }
}
