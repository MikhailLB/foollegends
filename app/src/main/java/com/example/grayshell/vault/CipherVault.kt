package com.example.grayshell.vault

/**
 * Lightweight XOR deobfuscator for sensitive strings (config URL, AppsFlyer
 * key, Firebase project number, GCD base URL).
 *
 * Key schedule: for position i the mask byte is
 *   seed[i % seedLen] XOR ((i * MULT + ADD) AND 0xFF)
 *
 * ░░ FINGERPRINT (mandatory per project) ░░
 *   Change [seedPhrase] AND the [MULT]/[ADD] mixing constants for every new
 *   app, then re-encode all secrets in AppBlueprint with the SAME scheme.
 *   Two apps must never share this seed/formula or identical encoded arrays.
 *
 * The matching encoder (run once, then paste arrays into AppBlueprint):
 *   for i in s.indices: enc[i] = s[i] XOR seed[i % n] XOR ((i*MULT+ADD) & 0xFF)
 */
internal object CipherVault {

    // TODO(you): replace with a unique short ASCII phrase for THIS project.
    private val seedPhrase: ByteArray = "ChangeThisSeed".toByteArray(Charsets.UTF_8)

    // TODO(you): change these two constants per project.
    private const val MULT = 37
    private const val ADD = 13

    fun reveal(obscured: IntArray): String {
        val buf = ByteArray(obscured.size)
        val n = seedPhrase.size
        for (i in obscured.indices) {
            val s = seedPhrase[i % n].toInt() and 0xFF
            val m = (i * MULT + ADD) and 0xFF
            buf[i] = ((obscured[i] and 0xFF) xor s xor m).toByte()
        }
        return buf.toString(Charsets.UTF_8)
    }
}
