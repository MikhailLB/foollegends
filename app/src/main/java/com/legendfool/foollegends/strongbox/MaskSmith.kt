package com.legendfool.foollegends.strongbox

/**
 * XOR deobfuscator for the endpoint, tracker key, project number and GCD root.
 *
 * Mask for position i:
 *   seed[(i * STRIDE + OFFSET) % seedLen] XOR ((i * MULT + ADD) AND 0xFF)
 *
 * The seed phrase and all four constants belong to this project only — a shared
 * schedule across apps is what links submissions together.
 */
internal object MaskSmith {

    private val seed = "MidnightHarlequinVII".toByteArray(Charsets.UTF_8)

    private const val STRIDE = 7
    private const val OFFSET = 3
    private const val MULT = 53
    private const val ADD = 97

    fun unveil(veiled: IntArray): String {
        if (veiled.isEmpty()) return ""
        val n = seed.size
        val out = ByteArray(veiled.size)
        for (i in veiled.indices) {
            val s = seed[(i * STRIDE + OFFSET) % n].toInt() and 0xFF
            val m = (i * MULT + ADD) and 0xFF
            out[i] = ((veiled[i] and 0xFF) xor s xor m).toByte()
        }
        return out.toString(Charsets.UTF_8)
    }
}
