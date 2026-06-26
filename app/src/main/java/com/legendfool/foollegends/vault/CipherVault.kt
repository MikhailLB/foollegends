package com.legendfool.foollegends.vault

/**
 * Lightweight XOR deobfuscator. Unique implementation — different seed phrase,
 * different key-mixing formula and different array format from any other project.
 *
 * Key schedule: for position i, the mask byte is
 *   seed[i % seedLen] XOR ((i * 37 + 13) AND 0xFF)
 *
 * Seed phrase: "FoolsGold@26#X"
 */
internal object CipherVault {

    private val seedPhrase: ByteArray = byteArrayOf(
        70, 111, 111, 108, 115, 71, 111, 108, 100, 64, 50, 54, 35, 88
    )

    fun reveal(obscured: IntArray): String {
        val buf = ByteArray(obscured.size)
        val n = seedPhrase.size
        for (i in obscured.indices) {
            val s = seedPhrase[i % n].toInt() and 0xFF
            val m = (i * 37 + 13) and 0xFF
            buf[i] = ((obscured[i] and 0xFF) xor s xor m).toByte()
        }
        return buf.toString(Charsets.UTF_8)
    }
}
