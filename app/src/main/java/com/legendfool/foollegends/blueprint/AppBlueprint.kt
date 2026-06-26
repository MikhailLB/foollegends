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

    // AppsFlyer dev key — fill after receiving key
    // Encode with: CipherVault.reveal(encoded) must return the dev key
    private val trackerKeyObs = intArrayOf() // TODO: fill after AppsFlyer key received

    // Firebase project number — fill after receiving Firebase credentials
    private val analyticsProjectObs = intArrayOf() // TODO: fill after Firebase setup

    @JvmStatic
    fun resolveConfigEndpoint(): String = CipherVault.reveal(configEndpointObs)

    @JvmStatic
    fun resolveTrackerKey(): String =
        if (trackerKeyObs.isEmpty()) "" else CipherVault.reveal(trackerKeyObs)

    @JvmStatic
    fun resolveAnalyticsProject(): String =
        if (analyticsProjectObs.isEmpty()) "" else CipherVault.reveal(analyticsProjectObs)
}
