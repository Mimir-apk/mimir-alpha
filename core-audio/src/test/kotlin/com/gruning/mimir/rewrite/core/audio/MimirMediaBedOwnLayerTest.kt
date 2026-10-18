package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mimir's own two players must never read as strangers to each other.
 *
 *
 *
 * Both halves are one defect. The frontend bed and Hero Motion are both Mimir's, but only ONE start
 * could be outstanding for correlation at a time, so the second player overwrote the first's claim.
 * The first then stayed unowned, `activePlaybackCount > ownedPlaybackCount` held, and the courtesy
 * gate meant for phone calls and other apps concluded a stranger was playing. That gate is binary —
 * it returns 0f — so the duck at FrontendMusicPolicy's `competingOwnedBed` never got to run, and the
 * music vanished instead of stepping back.
 *
 * Nothing exercised two own players colliding, which is why it shipped.
 */
class MimirMediaBedOwnLayerTest {

    private fun sample(active: Int, owned: Int, musicActive: Boolean = true) =
        MimirMediaBedEnvironmentSample(
            musicActive = musicActive,
            activePlaybackCount = active,
            ownedPlaybackCount = owned,
            callInterrupted = false,
            audioFocusLost = false,
        )

    @Test
    fun `two correlated own players are not an external environment`() {
        // Frontend bed + Hero Motion, both ours, both claimed. Nothing external is present, so the
        // binary gate must stay open and let the duck decide the level instead.
        assertFalse(mimirMediaBedEnvironmentBlocked(sample(active = 2, owned = 2)))
    }

    /**
     * The exact shape of the bug, pinned as a sample: two of ours playing but only one correlated.
     * This BLOCKS, and blocking is correct for this input — a genuinely unowned configuration should
     * hard-mute. The defect was never this predicate; it was upstream, in producing `owned = 1` when
     * both players were in fact ours.
     */
    @Test
    fun `an uncorrelated own player is indistinguishable from a stranger, and still blocks`() {
        assertTrue(mimirMediaBedEnvironmentBlocked(sample(active = 2, owned = 1)))
    }

    @Test
    fun `a real stranger alongside our players still blocks`() {
        assertTrue(mimirMediaBedEnvironmentBlocked(sample(active = 3, owned = 2)))
    }

    /**
     * The regression itself. Two own starts outstanding, two matching configurations appear: both
     * must be claimable in one batch. Before the fix only one start could be outstanding, so this
     * case could not arise and the second player was silently left unowned.
     */
    @Test
    fun `both own players are claimable when both starts are outstanding`() {
        assertTrue(
            mimirMediaBedClaimsWholeDelta(
                deltaCount = 2,
                ownCandidateCount = 2,
                pendingClaimCount = 2,
            ),
        )
    }

    @Test
    fun `the original single-player rule is unchanged`() {
        assertTrue(mimirMediaBedClaimsWholeDelta(1, 1, 1))
    }

    /**
     * The conservatism that must survive generalising to N. Public Android APIs expose no client UID
     * or session here, so anything ambiguous has to stay unowned and block. Claiming a foreign
     * configuration would make Mimir talk over a phone call or another app's music — a far worse
     * failure than muting itself.
     */
    @Test
    fun `an ambiguous batch is never claimed`() {
        // A configuration appeared that does not look like our bed at all.
        assertFalse(mimirMediaBedClaimsWholeDelta(deltaCount = 2, ownCandidateCount = 1, pendingClaimCount = 1))
        // More own-looking configurations than we have outstanding starts: one of them is a stranger
        // that merely resembles us.
        assertFalse(mimirMediaBedClaimsWholeDelta(deltaCount = 2, ownCandidateCount = 2, pendingClaimCount = 1))
        // Fewer appeared than we started: the rest are unaccounted for.
        assertFalse(mimirMediaBedClaimsWholeDelta(deltaCount = 1, ownCandidateCount = 1, pendingClaimCount = 2))
        // Nothing outstanding at all — an appearance we never initiated.
        assertFalse(mimirMediaBedClaimsWholeDelta(deltaCount = 1, ownCandidateCount = 1, pendingClaimCount = 0))
    }

    // ------------------------------------------------------------------------------------------
    // Sequencing across callbacks. The predicate above judges ONE batch, and it was green for the
    // entire life of the defect — a single-batch rule structurally cannot see what becomes of the
    // starts a batch failed to explain. Android hands us our two players in separate callbacks more
    // often than in one, so that is the path that actually has to work.
    // ------------------------------------------------------------------------------------------

    /** Stand-in for `AudioPlaybackConfiguration`, which is final and unconstructible in a host test. */
    private data class Config(val name: String, val own: Boolean)

    private val bed = Config("frontend-bed", own = true)
    private val heroMotion = Config("hero-motion", own = true)
    private val stranger = Config("spotify", own = false)

    private fun settle(
        relevant: List<Config>,
        oldestBaseline: List<Config> = emptyList(),
        pending: Int,
    ) = mimirMediaBedSettleBatch(
        relevant = relevant,
        oldestBaseline = oldestBaseline,
        pendingClaimCount = pending,
        isOwnCandidate = Config::own,
    )

    /**
     * The regression that survived widening the pending slot to a list, replayed as the two
     * callbacks Android actually delivers.
     *
     * Callback 1 cannot be explained on its own. The fix is that it reports so WITHOUT spending the
     * starts, so callback 2 still has both to correlate against.
     */
    @Test
    fun `two own players whose configurations arrive one callback apart are both correlated`() {
        val first = settle(relevant = listOf(bed), pending = 2)
        assertTrue(first.claimed.isEmpty())
        // The assertion that actually pins the defect: nothing was claimed, so nothing was spent.
        assertEquals(2, first.stillPending)

        val second = settle(relevant = listOf(bed, heroMotion), pending = first.stillPending)
        assertEquals(listOf(bed, heroMotion), second.claimed)
        assertEquals(0, second.stillPending)
    }

    /** The ordinary sequential case: the bed is already correlated when Hero Motion starts. */
    @Test
    fun `a second player starting later correlates against the already-claimed first`() {
        val outcome = settle(relevant = listOf(bed, heroMotion), oldestBaseline = listOf(bed), pending = 1)
        assertEquals(listOf(heroMotion), outcome.claimed)
        assertEquals(0, outcome.stillPending)
    }

    /**
     * A real stranger in the batch is never claimed, and never poisons the outstanding starts
     * either: when it goes away, our own players still correlate.
     */
    @Test
    fun `a stranger blocks the batch without consuming the outstanding starts`() {
        val alone = settle(relevant = listOf(bed, stranger), pending = 1)
        assertTrue(alone.claimed.isEmpty())
        assertEquals(1, alone.stillPending)

        val withBoth = settle(relevant = listOf(bed, heroMotion, stranger), pending = 2)
        assertTrue(withBoth.claimed.isEmpty())
        assertEquals(2, withBoth.stillPending)

        // Stranger gone; the very same outstanding starts now correlate.
        assertEquals(
            listOf(bed, heroMotion),
            settle(relevant = listOf(bed, heroMotion), pending = withBoth.stillPending).claimed,
        )
    }

    /** Nothing new since the baseline is not a claim opportunity — and must not spend the starts. */
    @Test
    fun `an empty delta claims nothing and spends nothing`() {
        val outcome = settle(relevant = listOf(bed), oldestBaseline = listOf(bed), pending = 1)
        assertTrue(outcome.claimed.isEmpty())
        assertEquals(1, outcome.stillPending)
    }

    /** An appearance we never initiated stays unowned however much it looks like us. */
    @Test
    fun `a configuration resembling ours is not claimed without an outstanding start`() {
        assertTrue(settle(relevant = listOf(bed), pending = 0).claimed.isEmpty())
        // More own-looking configurations than starts: one of them is a stranger that resembles us.
        val resembling = settle(relevant = listOf(bed, heroMotion), pending = 1)
        assertTrue(resembling.claimed.isEmpty())
        assertEquals(1, resembling.stillPending)
    }

    // ------------------------------------------------------------------------------------------
    // Re-keying. AudioPlaybackConfiguration.equals() folds in the player's STATE, so the same
    // player compares unequal the moment it changes state. Measured on an emulator at 73e50467:
    // the bed correlated (relevant=1 owned=1) and one millisecond later the claim was dropped
    // (relevant=1 owned=0) with the same MEDIA/MUSIC configuration still present. The bed then
    // paused and never returned.
    // ------------------------------------------------------------------------------------------

    /** Same player, same attributes, new object — as Android actually delivers it. */
    private fun churn(config: Config) = config.copy(name = config.name + "'")

    private fun rekey(
        claims: List<Config>,
        active: List<Boolean> = claims.map { true },
        relevant: List<Config>,
        previousRelevantCount: Int = relevant.size,
    ) = mimirMediaBedRekeyClaims(
        claimKeys = claims,
        claimActive = active,
        relevant = relevant,
        previousRelevantCount = previousRelevantCount,
        signature = { it.own },
    )

    @Test
    fun `a claim survives its player being handed back as a new object`() {
        val churned = churn(bed)
        assertEquals(listOf(churned), rekey(claims = listOf(bed), relevant = listOf(churned)))
    }

    /** An unchanged object must keep its own claim rather than being re-bound to something else. */
    @Test
    fun `an exact survivor keeps its own claim`() {
        assertEquals(listOf(bed), rekey(claims = listOf(bed), relevant = listOf(bed)))
    }

    /** A player that genuinely went away is still dropped — re-keying is not a way to never let go. */
    @Test
    fun `a claim whose player disappeared is dropped`() {
        assertEquals(listOf(null), rekey(claims = listOf(bed), relevant = emptyList()))
    }

    /**
     * The conservatism that matters most. A stranger playing music has exactly our attributes, so a
     * batch that GREW is unknowable: our churned player and the newcomer are indistinguishable.
     */
    @Test
    fun `nothing is re-bound when something new appeared in the same batch`() {
        assertEquals(
            listOf(null),
            rekey(
                claims = listOf(bed),
                relevant = listOf(churn(bed), heroMotion),
                previousRelevantCount = 1,
            ),
        )
    }

    /** A claim whose player is stopping must never migrate onto whatever else is playing music. */
    @Test
    fun `an inactive claim is not re-bound`() {
        assertEquals(
            listOf(null),
            rekey(claims = listOf(bed), active = listOf(false), relevant = listOf(churn(bed))),
        )
    }

    /** Two claims can never collapse onto one configuration. */
    @Test
    fun `two claims never re-bind to the same configuration`() {
        val result = rekey(
            claims = listOf(bed, heroMotion),
            relevant = listOf(churn(bed)),
            previousRelevantCount = 2,
        )
        assertEquals(1, result.count { it != null })
        assertEquals(listOf(churn(bed), null), result)
    }

    /** Both of our players churning at once still resolves to both, one configuration each. */
    @Test
    fun `both own players survive a simultaneous churn`() {
        val relevant = listOf(churn(bed), churn(heroMotion))
        assertEquals(relevant, rekey(claims = listOf(bed, heroMotion), relevant = relevant))
    }

    // ------------------------------------------------------------------------------------------
    // The backstop poll. Reported on an Odin 3 on 2026-08-10: hero-motion transitions could
    // silence music and prevent ambient and hero-motion audio from recovering. Measured: the gate
    // sat blocked with ZERO players anywhere on the device, and only an unrelated app switch
    // (which calls refresh()) brought it back.
    // ------------------------------------------------------------------------------------------

    // ------------------------------------------------------------------------------------------
    // Re-arming. Measured on an Odin 3 at 17:47:03 during a hero-motion to hero-motion pass: the
    // motion player's configuration disappeared (relevant=1 owned=1) and reappeared 119 ms later
    // (relevant=2 owned=1 pending=0) with nothing outstanding to correlate it. The bed was silenced
    // for 4.6 s until the backstop lifted it.
    // ------------------------------------------------------------------------------------------

    /** A dropped claim whose player is still running gets another chance at its own configuration. */
    @Test
    fun `a still-running player is re-armed when its configuration churns`() {
        assertEquals(
            listOf(19L),
            mimirMediaBedTokensToRearm(
                droppedTokens = listOf(19L),
                activeTokens = setOf(1L, 19L),
                alreadyPending = emptySet(),
            ),
        )
    }

    /** A player that genuinely stopped is NOT re-armed — that would be how a claim never dies. */
    @Test
    fun `a stopped player is not re-armed`() {
        assertEquals(
            emptyList<Long>(),
            mimirMediaBedTokensToRearm(
                droppedTokens = listOf(19L),
                activeTokens = setOf(1L),
                alreadyPending = emptySet(),
            ),
        )
    }

    /** Re-arming never duplicates an outstanding start, which would break the exact-count rule. */
    @Test
    fun `a token that is already outstanding is not re-armed again`() {
        assertEquals(
            emptyList<Long>(),
            mimirMediaBedTokensToRearm(
                droppedTokens = listOf(19L, 19L),
                activeTokens = setOf(19L),
                alreadyPending = setOf(19L),
            ),
        )
        // And a repeated drop of the same token yields exactly one outstanding start, not two.
        assertEquals(
            listOf(19L),
            mimirMediaBedTokensToRearm(listOf(19L, 19L), setOf(19L), emptySet()),
        )
    }

    /** A blocked gate must keep re-reading, because some blocked states have no event source. */
    @Test
    fun `a blocked environment schedules a backing-off re-read`() {
        val delays = (0 until MimirMediaBedRecheckDelaysMs.size).map {
            mimirMediaBedRecheckDelayMs(blocked = true, attempt = it)
        }
        assertEquals(MimirMediaBedRecheckDelaysMs.toList(), delays)
        // Strictly increasing, so this backs off rather than becoming a wakeup treadmill.
        assertEquals(delays, delays.sortedBy { it!! })
    }

    /** It gives up rather than polling forever — real external audio ends with a callback anyway. */
    @Test
    fun `the re-read ladder terminates`() {
        assertEquals(null, mimirMediaBedRecheckDelayMs(true, MimirMediaBedRecheckDelaysMs.size))
        assertEquals(null, mimirMediaBedRecheckDelayMs(true, 99))
    }

    /** An unblocked environment never schedules anything. */
    @Test
    fun `an unblocked environment schedules nothing`() {
        assertEquals(null, mimirMediaBedRecheckDelayMs(blocked = false, attempt = 0))
    }

    /**
     * With the environment gate no longer firing on our own audio, the duck is what decides the
     * level — which is the behaviour actually asked for: the bed steps back and resurfaces rather
     * than disappearing.
     */
    @Test
    fun `with no stranger present the bed ducks for Hero Motion instead of vanishing`() {
        val playing = FrontendMusicPolicyInput(
            appForeground = true,
            musicVolume = 0.5f,
            focus = AudioFocusState.Foreground,
            environmentBlocked = false,
            competingOwnedBed = false,
        )
        assertEquals(0.5f, FrontendMusicPolicy.targetVolume(playing), 0.0001f)

        val heroMotionAudible = playing.copy(competingOwnedBed = true)
        assertEquals(0.5f * 0.15f, FrontendMusicPolicy.targetVolume(heroMotionAudible), 0.0001f)

        // And the distinction that matters: a real stranger still silences it completely.
        assertEquals(0f, FrontendMusicPolicy.targetVolume(playing.copy(environmentBlocked = true)), 0f)
    }

    /** Nova 10-01: isMusicActive stayed true with nothing playing for up to ~34 s after Dolphin closed. */
    @Test
    fun `the re-read ladder spans about a minute and still ends`() {
        val total = MimirMediaBedRecheckDelaysMs.sum()
        assertTrue("total $total ms", total in 60_000L..61_000L)
        assertEquals(null, mimirMediaBedRecheckDelayMs(true, MimirMediaBedRecheckDelaysMs.size))
    }

    /** A ladder used up while a paused emulator sat in the background left nothing to re-read once it closed. */
    @Test
    fun `every playback change and every foreground edge start the re-read ladder over`() {
        val bed = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirMediaBed.kt").readText()
        val callback = bed.substringAfter("override fun onPlaybackConfigChanged(").substringBefore("acceptPlaybackConfigurationsLocked(")
        assertTrue(callback, callback.contains("blockedRecheckAttempt = 0"))
        val foreground = bed.substringAfter("fun refreshOnForeground() {").substringBefore("fun refresh() {")
        assertTrue(foreground, foreground.contains("blockedRecheckAttempt = 0") && foreground.contains("refresh()"))
        val music = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/MimirFrontendMusic.kt").readText()
        val onForeground = music.substringAfter("fun onAppForegrounded() {").substringBefore("fun onAppBackgrounded()")
        assertTrue(onForeground, onForeground.contains("MimirMediaBed.refreshOnForeground()"))
    }
}
