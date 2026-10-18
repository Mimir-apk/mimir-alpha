package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBackdropMediaSettleTest {
    @Test
    fun textureUpdateSuppliesFirstFrameWhenVendorOmitsRenderingStartInfo() {
        val started = VideoBackdropStartGate()
            .request(4L)
            .prepared(4L)
            .surface(true)
            .foreground(true)

        assertTrue(
            bifrostTextureFirstFrameAllowed(
                requestGeneration = 4L,
                currentGeneration = 4L,
                renderedGeneration = -1L,
                currentUri = "file:///a.mp4",
                startGate = started,
            ),
        )
        // The second compositor update is not a second first-frame notification.
        assertFalse(
            bifrostTextureFirstFrameAllowed(
                requestGeneration = 4L,
                currentGeneration = 4L,
                renderedGeneration = 4L,
                currentUri = "file:///a.mp4",
                startGate = started,
            ),
        )
    }

    @Test
    fun textureFallbackRejectsStaleGenerationAndCannotOpenAudioBeforeStartedFrame() {
        val notStarted = VideoBackdropStartGate().request(8L).prepared(8L).surface(true)
        assertFalse(
            bifrostTextureFirstFrameAllowed(8L, 8L, -1L, "file:///b.mp4", notStarted),
        )
        val current = notStarted.foreground(true)
        assertFalse(
            bifrostTextureFirstFrameAllowed(7L, 8L, -1L, "file:///a.mp4", current),
        )
        assertTrue(
            bifrostTextureFirstFrameAllowed(8L, 8L, -1L, "file:///b.mp4", current),
        )
    }

    @Test
    fun transientKindProbeRetainsConfirmedVideoControllerAcrossAToBToA() {
        val a = "file:///a.mp4"
        val b = "file:///b.mp4"
        assertEquals(a, bifrostRetainedVideoSurfaceUri(BifrostLocalMediaKind.VIDEO, a, null))
        // B is probing: preserve A's controller/surface, but never claim B is visual.
        assertEquals(a, bifrostRetainedVideoSurfaceUri(null, b, a))
        // Returning to A while B remains unresolved still reuses the same controller identity.
        assertEquals(a, bifrostRetainedVideoSurfaceUri(null, a, a))
        assertEquals(b, bifrostRetainedVideoSurfaceUri(BifrostLocalMediaKind.VIDEO, b, a))
        assertNull(bifrostRetainedVideoSurfaceUri(BifrostLocalMediaKind.ANIMATED_IMAGE, b, a))
        assertNull(bifrostRetainedVideoSurfaceUri(null, null, a))
    }

    @Test
    fun confirmedVideoUriCreatesADistinctSurfaceEpochAndTextureFallbackDoesNotRequireSizeCallback() {
        val bStarted = VideoBackdropStartGate()
            .request(2L)
            .prepared(2L)
            .surface(true)
            .foreground(true)
        assertTrue(bifrostVideoSurfaceEpoch("file:///a.mp4") != bifrostVideoSurfaceEpoch("file:///b.mp4"))
        // B owns a fresh TextureView/controller epoch, so its first real texture update is sufficient;
        // video-size callback ordering is vendor-specific and not a first-frame prerequisite.
        assertTrue(
            bifrostTextureFirstFrameAllowed(2L, 2L, -1L, "file:///b.mp4", bStarted),
        )
    }

    @Test
    fun surfaceBindingKeepsRetainedAOwnedByAUntilBIsConfirmed() {
        val a = BifrostVideoSurfaceBinding("file:///a.mp4", selectionGeneration = 1L)
        val b = BifrostVideoSurfaceBinding("file:///b.mp4", selectionGeneration = 2L)

        // B's media-kind probe is pending. A may remain mounted, but it must not be relabelled as B.
        assertEquals(
            a,
            bifrostRetainedVideoSurfaceBinding(
                mediaKind = null,
                playerUri = b.uri,
                playerGeneration = b.selectionGeneration,
                retainedBinding = a,
            ),
        )
        assertEquals(
            b,
            bifrostRetainedVideoSurfaceBinding(
                mediaKind = BifrostLocalMediaKind.VIDEO,
                playerUri = b.uri,
                playerGeneration = b.selectionGeneration,
                retainedBinding = a,
            ),
        )
        assertTrue(
            bifrostMediaSurfaceEpoch(a.uri, a.selectionGeneration) !=
                bifrostMediaSurfaceEpoch(a.uri, a.selectionGeneration + 2L),
        )
    }

    @Test
    fun videoStartsOnlyAfterPreparedSurfaceAndForegroundInEitherEventOrder() {
        val preparedThenSurface = VideoBackdropStartGate()
            .request(7L)
            .prepared(7L)
            .surface(true)
            .foreground(true)
        assertTrue(preparedThenSurface.mayStart(7L))

        val surfaceThenPrepared = VideoBackdropStartGate()
            .request(7L)
            .surface(true)
            .foreground(false)
            .prepared(7L)
        assertFalse(surfaceThenPrepared.mayStart(7L))
        assertTrue(surfaceThenPrepared.foreground(true).mayStart(7L))
    }

    @Test
    fun coldStoppedMountCannotStartUntilExplicitForegroundArrival() {
        val coldStopped = VideoBackdropStartGate()
            .request(4L)
            .prepared(4L)
            .surface(true)
        assertFalse(coldStopped.mayStart(4L))
        assertTrue(coldStopped.foreground(true).mayStart(4L))
    }

    @Test
    fun surfaceLossStopsAdmissionAndOnlyCurrentPreparedGenerationMayResume() {
        val playing = VideoBackdropStartGate()
            .request(8L)
            .prepared(8L)
            .surface(true)
            .foreground(true)
        assertTrue(playing.mayStart(8L))

        val lost = playing.surface(false)
        assertFalse(lost.mayStart(8L))
        assertTrue(lost.surface(true).mayStart(8L))

        val next = lost.request(9L).surface(true)
        assertFalse(next.mayStart(8L))
        assertFalse(next.mayStart(9L))
        assertTrue(next.prepared(9L).mayStart(9L))
    }

    @Test
    fun stalePreparedGenerationCannotStartAfterANoneAReturn() {
        var gate = VideoBackdropStartGate()
            .surface(true)
            .foreground(true)
            .request(1L)
            .prepared(1L)
        assertTrue(gate.mayStart(1L))

        gate = gate.request(2L) // A → none
        assertFalse(gate.mayStart(1L))
        assertFalse(gate.prepared(1L).mayStart(1L))

        gate = gate.request(3L).prepared(3L) // none → A (new generation)
        assertFalse(gate.mayStart(1L))
        assertTrue(gate.mayStart(3L))
    }
    @Test
    fun settleAndVisualHandoffStayInsideTheBifrostContract() {
        assertEquals(300L, BifrostMediaSettleMs)
        assertTrue(BifrostVisualHandoffMs <= 140)
    }

    @Test
    fun rapidNavigationSettlesOnlyTheLatestRequest() {
        var state = BifrostMediaState()
        val a = state.request("file:///a.mp4")
        state = a
        val b = state.request("file:///b.mp4")
        state = b
        val c = state.request("file:///c.mp4")
        state = c

        val afterStaleA = state.settle(a.generation)
        assertSame(state, afterStaleA)
        val afterStaleB = state.settle(b.generation)
        assertSame(state, afterStaleB)

        state = state.settle(c.generation)
        assertEquals("file:///c.mp4", state.playerUri)
        assertFalse(state.videoVisible)

        state = state.onFirstFrame("file:///b.mp4", c.generation)
        assertFalse(state.videoVisible)
        state = state.onFirstFrame("file:///c.mp4", c.generation)
        assertTrue(state.videoVisible)
    }

    @Test
    fun aToBToAReturnRejectsTheFirstAFrameUntilTheFinalASelectionRenders() {
        val a = "file:///a.mp4"
        var state = BifrostMediaState()
        val firstA = state.request(a)
        state = firstA.settle(firstA.generation).onFirstFrame(a, firstA.generation)
        assertTrue(state.videoVisible)

        state = state.request("file:///b.mp4")
        val finalA = state.request(a)
        state = finalA.settle(finalA.generation)
        assertFalse(state.videoVisible)

        // Same URI is insufficient: this is the old controller/frame generation.
        assertSame(state, state.onFirstFrame(a, firstA.generation))
        assertFalse(state.videoVisible)

        state = state.onFirstFrame(a, finalA.generation)
        assertTrue(state.videoVisible)
    }

    @Test
    fun audioRampsForAboutTwoHundredMsOnlyAfterAFrameIsVisible() {
        assertEquals(200, BifrostAudioFadeMs)
        assertEquals(0, bifrostBedRampDurationMs(videoVisible = false))
        assertEquals(BifrostAudioFadeMs, bifrostBedRampDurationMs(videoVisible = true))
    }

    @Test
    fun aNewRequestHidesTheOldFrameBeforeThePlayerRebinds() {
        var state = BifrostMediaState()
        state = state.request("file:///a.mp4")
        state = state.settle(state.generation).onFirstFrame("file:///a.mp4", state.generation)
        assertTrue(state.videoVisible)

        state = state.request("file:///b.mp4")

        assertEquals("file:///a.mp4", state.playerUri)
        assertFalse(state.videoVisible)
        assertEquals("file:///b.mp4", state.requestedUri)
    }

    @Test
    fun staticArtPresentationKeyIsDestinationAndShapeSpecific() {
        val cover = bifrostArtPresentationKey("file:///a.webp", artIsLogo = false)
        val logo = bifrostArtPresentationKey("file:///a.webp", artIsLogo = true)
        val next = bifrostArtPresentationKey("file:///b.webp", artIsLogo = false)

        assertTrue(cover != logo)
        assertTrue(cover != next)
        assertEquals(cover, bifrostArtPresentationKey("file:///a.webp", artIsLogo = false))
    }

    @Test
    fun noVideoRequestClearsThePlayerOnlyWhenItsGenerationSettles() {
        var state = BifrostMediaState()
        state = state.request("file:///a.mp4")
        state = state.settle(state.generation).onFirstFrame("file:///a.mp4", state.generation)

        val clearRequest = state.request(null)
        state = clearRequest
        assertFalse(state.videoVisible)
        assertEquals("file:///a.mp4", state.playerUri)

        state = state.settle(clearRequest.generation)
        assertNull(state.playerUri)
        assertNull(state.renderedUri)
    }

    @Test
    fun onlyLocalNonBlankUrisAreEligibleAndPowerSaveWins() {
        assertEquals("file:///preview.mp4", eligibleBifrostVideoUri("file:///preview.mp4", powerSave = false))
        assertEquals("content://mimir/preview", eligibleBifrostVideoUri("content://mimir/preview", powerSave = false))
        assertNull(eligibleBifrostVideoUri("https://example.invalid/preview.mp4", powerSave = false))
        assertNull(eligibleBifrostVideoUri("HTTP://example.invalid/preview.mp4", powerSave = false))
        assertNull(eligibleBifrostVideoUri("", powerSave = false))
        assertNull(eligibleBifrostVideoUri("file:///preview.mp4", powerSave = true))
    }

    @Test
    fun previewClipCropConservesAspectAcrossBackdropShapes() {
        assertEquals(
            BifrostAspectCrop(1f, 1f),
            bifrostAspectPreservingCrop(1920, 1080, 1920, 1080),
        )
        assertEquals(
            BifrostAspectCrop(1f, 4f / 3f),
            bifrostAspectPreservingCrop(1920, 1080, 640, 480),
        )
        assertEquals(
            BifrostAspectCrop(21f / 16f, 1f),
            bifrostAspectPreservingCrop(1920, 1080, 2520, 1080),
        )
        assertNull(bifrostAspectPreservingCrop(0, 1080, 1920, 1080))
    }

    @Test
    fun animatedDecodeRejectsStaleAAfterLatestB() {
        val a = AnimatedImageDecodeRequest(rawUri = "file:///a.webp", generation = 1L)
        val b = AnimatedImageDecodeRequest(rawUri = "file:///b.webp", generation = 2L)

        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = a,
                latestRawUri = b.rawUri,
                latestGeneration = b.generation,
                foreground = true,
                released = false,
            ),
        )
        assertTrue(
            acceptAnimatedImageDecodeResult(
                request = b,
                latestRawUri = b.rawUri,
                latestGeneration = b.generation,
                foreground = true,
                released = false,
            ),
        )
    }

    @Test
    fun animatedDecodeTreatsFragmentVariantsAsDistinctRawIdentitiesSharingSource() {
        val withA = "file:///clip.webp#seat-a"
        val withB = "file:///clip.webp#seat-b"
        assertEquals("file:///clip.webp", bifrostAnimatedImageSourceUri(withA))
        assertEquals(bifrostAnimatedImageSourceUri(withA), bifrostAnimatedImageSourceUri(withB))
        assertEquals(withA, AnimatedImageDecodeRequest(withA, 1L).rawUri)
        assertEquals("file:///clip.webp", AnimatedImageDecodeRequest(withA, 1L).sourceUri)

        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = AnimatedImageDecodeRequest(rawUri = withA, generation = 1L),
                latestRawUri = withB,
                latestGeneration = 2L,
                foreground = true,
                released = false,
            ),
        )
        assertTrue(
            acceptAnimatedImageDecodeResult(
                request = AnimatedImageDecodeRequest(rawUri = withB, generation = 2L),
                latestRawUri = withB,
                latestGeneration = 2L,
                foreground = true,
                released = false,
            ),
        )
    }

    @Test
    fun animatedDecodeRejectsBackgroundAndReleasedResults() {
        val request = AnimatedImageDecodeRequest(rawUri = "file:///a.webp", generation = 1L)
        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = request,
                latestRawUri = request.rawUri,
                latestGeneration = request.generation,
                foreground = false,
                released = false,
            ),
        )
        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = request,
                latestRawUri = request.rawUri,
                latestGeneration = request.generation,
                foreground = true,
                released = true,
            ),
        )
        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = request,
                latestRawUri = null,
                latestGeneration = request.generation,
                foreground = true,
                released = false,
            ),
        )
        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = request,
                latestRawUri = "",
                latestGeneration = request.generation,
                foreground = true,
                released = false,
            ),
        )
    }

    @Test
    fun animatedDecodeAcceptsMatchingLatestForegroundResult() {
        val request = AnimatedImageDecodeRequest(rawUri = "file:///a.webp#g3", generation = 3L)
        assertTrue(
            acceptAnimatedImageDecodeResult(
                request = request,
                latestRawUri = "file:///a.webp#g3",
                latestGeneration = 3L,
                foreground = true,
                released = false,
            ),
        )
        assertFalse(
            acceptAnimatedImageDecodeResult(
                request = request,
                latestRawUri = "file:///a.webp#g3",
                latestGeneration = 4L,
                foreground = true,
                released = false,
            ),
        )
    }

    @Test
    fun staleVideoProbeForAIsUnusableUntilBAnimatedProbeMatchesSettledPlayer() {
        val a = "file:///a.mp4"
        val b = "file:///b.webp"
        val probeA = BifrostMediaKindProbe(rawUri = a, kind = BifrostLocalMediaKind.VIDEO)

        // Pending B: settle has not published B yet; retained A probe must not drive the surface.
        var state = BifrostMediaState().request(b)
        assertNull(usableBifrostMediaKind(probeA, state.playerUri))
        assertNull(usableBifrostMediaKind(probeA, b))

        // Settled B: player is B, but produceState may still retain A's VIDEO probe.
        state = state.settle(state.generation)
        assertEquals(b, state.playerUri)
        assertNull(usableBifrostMediaKind(probeA, state.playerUri))

        val probeB = BifrostMediaKindProbe(rawUri = b, kind = BifrostLocalMediaKind.ANIMATED_IMAGE)
        assertEquals(
            BifrostLocalMediaKind.ANIMATED_IMAGE,
            usableBifrostMediaKind(probeB, state.playerUri),
        )
        assertNull(usableBifrostMediaKind(null, state.playerUri))
        assertNull(usableBifrostMediaKind(probeB, null))
    }

    @Test
    fun animatedSurfaceRetainsAcceptedAAcrossStopResumeWithoutDecode() {
        val a = "file:///a.webp"
        assertEquals(
            AnimatedImageSurfaceDecision.DECODE,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = a, foreground = true, adoptedRawUri = null),
            ),
        )
        assertEquals(
            AnimatedImageSurfaceDecision.STOP_RETAIN,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = a, foreground = false, adoptedRawUri = a),
            ),
        )
        assertEquals(
            AnimatedImageSurfaceDecision.RESUME_WITHOUT_DECODE,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = a, foreground = true, adoptedRawUri = a),
            ),
        )
    }

    @Test
    fun animatedSurfaceRejectsInFlightAOnBackgroundAndLetsBWin() {
        val a = "file:///a.webp"
        val b = "file:///b.webp"
        // In-flight A already cleared adoption at begin — background must CLEAR (invalidate rejects).
        assertEquals(
            AnimatedImageSurfaceDecision.CLEAR,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = a, foreground = false, adoptedRawUri = null),
            ),
        )
        assertEquals(
            AnimatedImageSurfaceDecision.DECODE,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = b, foreground = true, adoptedRawUri = a),
            ),
        )
        assertEquals(
            AnimatedImageSurfaceDecision.CLEAR,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = b, foreground = false, adoptedRawUri = a),
            ),
        )
        assertEquals(
            AnimatedImageSurfaceDecision.CLEAR,
            decideAnimatedImageSurface(
                AnimatedImageSurfaceInputs(rawUri = null, foreground = true, adoptedRawUri = a),
            ),
        )
    }
}
