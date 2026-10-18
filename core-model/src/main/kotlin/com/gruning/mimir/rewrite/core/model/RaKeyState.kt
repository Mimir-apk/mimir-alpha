package com.gruning.mimir.rewrite.core.model

/**
 * What Mimir may honestly claim about the stored RetroAchievements credential.
 *
 * Acceptance is durable because it followed a real service response. Rejection is deliberately scoped to
 * this process: an RA-side outage must not permanently accuse a key or force the user to transcribe it again.
 */
enum class RaKeyState {
    NEVER_CHECKED,
    ACCEPTED,
    REJECTED_THIS_SESSION,
}
