package com.gruning.mimir.tweaker

/**
 * SAFE posture: discovery is this device's own hardware and nothing else.
 *
 * The identity body of the function `src/lab/kotlin` overrides. The union type it returns there is not
 * compiled into this APK at all, so the synthetic-axis code path is an ABSENT SYMBOL here rather than a
 * branch that happens never to be taken. That is the property `:app:compileSafeDebugKotlin` checks for
 * free: if the lab-only type were ever referenced from `src/main`, this compilation would fail.
 */
internal fun composeDiscoveredAxisAdmission(discovered: DiscoveredAxisAdmission): DiscoveredAxisAdmission =
    discovered
