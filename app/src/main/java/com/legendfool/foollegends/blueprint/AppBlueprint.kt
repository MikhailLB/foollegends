package com.legendfool.foollegends.blueprint

import com.legendfool.foollegends.vault.CipherVault

/**
 * Central configuration for Fool Legends. Sensitive values are XOR-obfuscated;
 * plain strings are non-sensitive metadata.
 *
 * Keys to fill in after receiving credentials:
 *  - [resolveTrackerKey] → AppsFlyer dev key
 *  - [resolveAnalyticsProject] → Firebase project number
 */
object AppBlueprint {

    const val bundleId    = "com.legendfool.foollegends"
    const val appLabel    = "Fool Legends"
    const val minSdkVer   = 24
    const val attributionTimeoutMs = 15_000L
    const val configTimeoutMs      = 15_000L
    const val organicGcdDelayMs    = 5_000L

    // Encoded config endpoint: "https://foollegends.com/config.php"
    // Decoded at runtime via CipherVault — never stored in plaintext
    private val configEndpointObs = intArrayOf(
        35, 41, 76, 96, 161, 187, 171, 83, 55, 117, 34, 254, 134, 211,
        50, 50, 92, 138, 167, 165, 253, 21, 50, 15, 212, 243, 130, 202,
        54, 54, 34, 148, 182, 229
    )

    // AppsFlyer dev key: "LMTBWoGkhUJXippZVcPNMf" — XOR-encoded, CipherVault seed "FoolsGold@26#X"
    private val trackerKeyObs = intArrayOf(
        7, 16, 108, 82, 133, 238, 195, 23, 57, 79, 7, 202, 131, 198, 37, 13, 100, 141, 132, 197, 211, 28
    )

    // Firebase project number: "1040007503051"
    private val analyticsProjectObs = intArrayOf(
        122, 109, 12, 32, 226, 177, 179, 73, 97, 41, 125, 167, 219
    )

    @JvmStatic
    fun resolveConfigEndpoint(): String = CipherVault.reveal(configEndpointObs)

    @JvmStatic
    fun resolveTrackerKey(): String =
        if (trackerKeyObs.isEmpty()) "" else CipherVault.reveal(trackerKeyObs)

    @JvmStatic
    fun resolveAnalyticsProject(): String =
        if (analyticsProjectObs.isEmpty()) "" else CipherVault.reveal(analyticsProjectObs)
}
