package com.legendfool.foollegends.charter

import com.legendfool.foollegends.BuildConfig
import com.legendfool.foollegends.strongbox.MaskSmith

/** Central configuration facade. Sensitive strings are masked via [MaskSmith]. */
object AppCharter {

    const val bundleId = BuildConfig.APPLICATION_ID
    const val appNameToken = "FoolLegends"

    const val traceFirstMs = 30_000L
    const val traceReturnMs = 10_000L
    const val deepLinkWaitMs = 5_000L
    const val landingTimeoutMs = 15_000L
    const val organicRetryDelayMs = 5_000L
    const val gcdTimeoutMs = 10_000L

    /** https://foollegends.com/config.php */
    private val landingEndpointVeil = intArrayOf(
        103, 144, 233, 25, 42, 25, 215, 158, 38, 57, 109, 137, 197, 2, 73, 81, 182,
        230, 9, 16, 136, 167, 212, 98, 86, 168, 202, 251, 13, 109, 200, 241, 29, 51
    )

    /** AppsFlyer dev key */
    private val traceKeyVeil = intArrayOf(
        67, 169, 201, 43, 14, 76, 191, 218, 40, 3, 72, 189, 192, 23, 94, 110, 142,
        225, 42, 112, 166, 174
    )

    /** Firebase project number */
    private val signalProjectVeil = intArrayOf(
        62, 212, 169, 89, 105, 19, 207, 132, 112, 101, 50, 208, 152
    )

    /** https://gcdsdk.appsflyer.com/install_data/v4.0/ */
    private val gcdRootVeil = intArrayOf(
        103, 144, 233, 25, 42, 25, 215, 158, 39, 53, 102, 150, 205, 12, 0, 85, 168,
        242, 9, 88, 135, 177, 220, 63, 27, 164, 203, 240, 75, 99, 136, 242, 1, 34,
        110, 132, 227, 10, 63, 110, 166, 131, 51, 21, 63, 203, 175
    )

    fun landingEndpoint(): String = MaskSmith.unveil(landingEndpointVeil)
    fun traceKey(): String = MaskSmith.unveil(traceKeyVeil)
    fun signalProject(): String = MaskSmith.unveil(signalProjectVeil)
    fun gcdRoot(): String = MaskSmith.unveil(gcdRootVeil)

    /** Bypasses the backend during bring-up. MUST stay empty in release. */
    const val forcedLandingUrl = ""
}
