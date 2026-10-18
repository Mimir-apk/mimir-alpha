package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.AndroidGameLaunchProofStore
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName

/** One successful Android-game dispatch truth shared by Home and every Library-native launch surface. */
internal fun androidGameDispatchProofFor(
    packageName: String,
    timestampMillis: Long,
): AndroidGameLaunchProof = AndroidGameLaunchProof(
    packageName = AndroidPackageName(packageName),
    outcome = LaunchOutcome.IntentSent,
    timestampMillis = timestampMillis,
)

/** Persistence failure is returned to the caller and can never rewrite the already-settled launch outcome. */
internal suspend fun recordAndroidGameDispatchRecency(
    store: AndroidGameLaunchProofStore,
    packageName: String,
    timestampMillis: Long,
): Result<Unit> = runCatching {
    store.record(androidGameDispatchProofFor(packageName, timestampMillis))
}
