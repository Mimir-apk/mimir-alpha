package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AU02 — the media bed's pure governance ([MediaBedPolicy]), exhaustively pinned like
 * [MimirAudioPolicyTest]: every gate silences the bed alone; only a fully-clear environment
 * passes the (clamped) mix through.
 */
class MediaBedPolicyTest {

    @Test fun ownAudioGraceUsesItsInjectedMonotonicClock() {
        var now = 100L
        val grace = MimirMediaBedOwnAudioGrace(clockMs = { now }, graceMs = 2500L)
        assertEquals(false, grace.likelyOwnAudio())
        grace.noteAudible()
        now += 2500L
        assertEquals(true, grace.likelyOwnAudio())
        now += 1L
        assertEquals(false, grace.likelyOwnAudio())
    }

    @Test fun externalPlaybackDuringOwnedTailGraceStillBlocks() {
        assertEquals(
            true,
            mimirMediaBedEnvironmentBlocked(
                MimirMediaBedEnvironmentSample(
                    musicActive = true,
                    activePlaybackCount = 2,
                    ownedPlaybackCount = 1,
                    callInterrupted = false,
                    audioFocusLost = false,
                ),
            ),
        )
        assertEquals(
            false,
            mimirMediaBedEnvironmentBlocked(
                MimirMediaBedEnvironmentSample(
                    musicActive = true,
                    activePlaybackCount = 2,
                    ownedPlaybackCount = 2,
                    callInterrupted = false,
                    audioFocusLost = false,
                ),
            ),
        )
    }

    @Test fun callOrAudioFocusLossAlwaysBlocksEvenWhenEveryPlaybackIsOwned() {
        val ownedOnly = MimirMediaBedEnvironmentSample(
            musicActive = true,
            activePlaybackCount = 1,
            ownedPlaybackCount = 1,
            callInterrupted = false,
            audioFocusLost = false,
        )
        assertEquals(true, mimirMediaBedEnvironmentBlocked(ownedOnly.copy(callInterrupted = true)))
        assertEquals(true, mimirMediaBedEnvironmentBlocked(ownedOnly.copy(audioFocusLost = true)))
        // Android exposes no owner for isMusicActive without a corresponding public configuration.
        assertEquals(
            true,
            mimirMediaBedEnvironmentBlocked(
                ownedOnly.copy(activePlaybackCount = 0, ownedPlaybackCount = 0),
            ),
        )
    }

    @Test fun retainedUiSoundPoolDoesNotMasqueradeAsExternalMusic() {
        assertEquals(
            false,
            mimirMediaBedCourtesyRelevantContent(
                android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION,
            ),
        )
        assertEquals(
            true,
            mimirMediaBedCourtesyRelevantContent(
                android.media.AudioAttributes.CONTENT_TYPE_MOVIE,
            ),
        )
        assertEquals(
            false,
            mimirMediaBedMusicActiveForCourtesy(
                musicActive = true,
                observedPlaybackCount = 1,
                relevantPlaybackCount = 0,
            ),
        )
        // No public configuration remains deliberately ambiguous and fail-closed.
        assertEquals(
            true,
            mimirMediaBedMusicActiveForCourtesy(
                musicActive = true,
                observedPlaybackCount = 0,
                relevantPlaybackCount = 0,
            ),
        )
    }

    @Test fun exactOwnedStartSurvivesOnlyTheBoundedVendorConfigurationGap() {
        assertEquals(
            true,
            mimirMediaBedRetainOwnedStartGap(
                relevantPlaybackCount = 0,
                activeOwnedPlaybackCount = 1,
                gapElapsedMs = MimirMediaBed.OwnActiveClaimGapGraceMs,
            ),
        )
        assertEquals(
            false,
            mimirMediaBedRetainOwnedStartGap(
                relevantPlaybackCount = 0,
                activeOwnedPlaybackCount = 1,
                gapElapsedMs = MimirMediaBed.OwnActiveClaimGapGraceMs + 1,
            ),
        )
        assertEquals(
            false,
            mimirMediaBedRetainOwnedStartGap(
                relevantPlaybackCount = 1,
                activeOwnedPlaybackCount = 1,
                gapElapsedMs = 0,
            ),
        )
        assertEquals(
            false,
            mimirMediaBedRetainOwnedStartGap(
                relevantPlaybackCount = 0,
                activeOwnedPlaybackCount = 0,
                gapElapsedMs = 0,
            ),
        )
    }

    private fun volume(
        enabled: Boolean = true,
        mix: Float = 0.35f,
        focus: AudioFocusState = AudioFocusState.Foreground,
        environmentBlocked: Boolean = false,
    ): Float = MediaBedPolicy.bedVolume(enabled, mix, focus, environmentBlocked)

    @Test fun clear_environment_passes_the_mix_through() {
        assertEquals(0.35f, volume(), 0f)
    }

    @Test fun disabled_preference_silences() {
        assertEquals(0f, volume(enabled = false), 0f)
    }

    @Test fun suspended_focus_silences_games_own_audio() {
        assertEquals(0f, volume(focus = AudioFocusState.Suspended), 0f)
    }

    @Test fun blocked_environment_silences_courtesy_gate() {
        assertEquals(0f, volume(environmentBlocked = true), 0f)
    }

    @Test fun every_gate_wins_over_an_audible_mix() {
        assertEquals(0f, volume(enabled = false, mix = 1f), 0f)
        assertEquals(0f, volume(focus = AudioFocusState.Suspended, mix = 1f), 0f)
        assertEquals(0f, volume(environmentBlocked = true, mix = 1f), 0f)
    }

    @Test fun mix_is_clamped_to_unit_range() {
        assertEquals(1f, volume(mix = 1.6f), 0f)
        assertEquals(0f, volume(mix = -0.4f), 0f)
    }
}
