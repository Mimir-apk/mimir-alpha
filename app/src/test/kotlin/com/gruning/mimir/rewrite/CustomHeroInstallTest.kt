package com.gruning.mimir.rewrite

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UXP-01 Custom Hero atomicity (review blockers, 2026-07-19).
 *
 * The first draft of `applyHero` had real atomicity defects, and Pass 2 later separated stills/clips:
 *  1. `renameTo` fails when the destination exists, so replacing a same-kind hero silently no-op'd.
 *  2. a chosen still and chosen clip must now coexist; installing either cannot erase its sibling.
 *  3. non-empty bytes were accepted as valid media, so an HTTP-200 error page could be committed.
 *
 * These pin the fixed contract: previous media survives every failure path and both kinds coexist.
 */
class CustomHeroInstallTest {

    @get:Rule val folder = TemporaryFolder()

    private fun file(name: String, content: String = "bytes"): File =
        folder.newFile(name).apply { writeText(content) }

    private fun install(
        temp: File,
        target: File,
        opposite: File,
        validate: (File) -> Boolean = { true },
        move: (File, File) -> Boolean = { from, to ->
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        },
        backupCopy: (File, File) -> Boolean = { from, to ->
            from.copyTo(to, overwrite = true)
            true
        },
        onCommit: () -> Boolean = { true },
    ) = installHeroAsset(
        temp = temp,
        target = target,
        validate = validate,
        commit = onCommit,
        move = move,
        backupCopy = backupCopy,
    )

    @Test
    fun `replacing an existing same-kind hero actually replaces it`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")
        var committed = false

        val outcome = install(temp, target, opposite, onCommit = { committed = true; true })

        assertEquals(HeroInstallOutcome.INSTALLED, outcome)
        assertEquals("NEW", target.readText())
        assertTrue(committed)
        assertFalse("the temp file must not linger", temp.exists())
        assertFalse("the backup must be cleaned up", File(folder.root, "hero.img.bak").exists())
    }

    @Test
    fun `existing target remains present until the atomic replacement`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")
        var observedOldTarget = false

        val outcome = install(
            temp = temp,
            target = target,
            opposite = opposite,
            move = { from, to ->
                observedOldTarget = to.isFile && to.readText() == "OLD"
                Files.move(
                    from.toPath(),
                    to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            },
        )

        assertEquals(HeroInstallOutcome.INSTALLED, outcome)
        assertTrue(observedOldTarget)
        assertEquals("NEW", target.readText())
    }

    @Test
    fun `invalid media never replaces the live hero and never commits`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "<html>error</html>")
        val opposite = File(folder.root, "hero.mp4")
        var committed = false

        val outcome = install(
            temp,
            target,
            opposite,
            validate = { false },
            onCommit = { committed = true; true },
        )

        assertEquals(HeroInstallOutcome.INVALID_MEDIA, outcome)
        assertEquals("the previous hero must survive a bad download", "OLD", target.readText())
        assertFalse("a rejected asset must not be committed", committed)
        assertFalse(temp.exists())
    }

    @Test
    fun `a failed atomic promotion leaves the previous hero in place`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")
        var committed = false

        val outcome = install(
            temp, target, opposite,
            move = { _, _ -> false },
            onCommit = { committed = true; true },
        )

        assertEquals(HeroInstallOutcome.MOVE_FAILED, outcome)
        assertTrue("the previous hero must be restored", target.isFile)
        assertEquals("OLD", target.readText())
        assertFalse(committed)
    }

    @Test
    fun `a failed rollback backup copy aborts before touching anything`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")
        var committed = false

        val outcome = install(
            temp,
            target,
            opposite,
            backupCopy = { _, _ -> false },
            onCommit = { committed = true; true },
        )

        assertEquals(HeroInstallOutcome.MOVE_FAILED, outcome)
        assertEquals("OLD", target.readText())
        assertFalse(committed)
    }

    @Test
    fun `a failed static to video switch keeps the existing static hero`() {
        // The exact regression: switching kinds used to delete the opposite-kind asset first, so a
        // failure left the game with NO hero at all.
        val existingStatic = file("hero.img", "STATIC")
        val videoTarget = File(folder.root, "hero.mp4")
        val temp = file("hero.mp4.part", "not-a-video")
        var committed = false

        val outcome = install(
            temp = temp,
            target = videoTarget,
            opposite = existingStatic,
            validate = { false },
            onCommit = { committed = true; true },
        )

        assertEquals(HeroInstallOutcome.INVALID_MEDIA, outcome)
        assertTrue("the working static hero must still exist", existingStatic.isFile)
        assertEquals("STATIC", existingStatic.readText())
        assertFalse(videoTarget.exists())
        assertFalse(committed)
    }

    @Test
    fun `installing a video keeps the independently chosen static hero`() {
        val existingStatic = file("hero.img", "STATIC")
        val videoTarget = File(folder.root, "hero.mp4")
        val temp = file("hero.mp4.part", "VIDEO")
        var staticAliveAtCommit = false

        val outcome = install(
            temp = temp,
            target = videoTarget,
            opposite = existingStatic,
            onCommit = { staticAliveAtCommit = existingStatic.isFile; true },
        )

        assertEquals(HeroInstallOutcome.INSTALLED, outcome)
        assertEquals("VIDEO", videoTarget.readText())
        assertTrue("the static hero must exist at commit time", staticAliveAtCommit)
        assertTrue("and remain as the clip's independent fallback", existingStatic.exists())
    }

    @Test
    fun `installing a first hero with no previous asset works`() {
        val target = File(folder.root, "hero.img")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")

        assertEquals(HeroInstallOutcome.INSTALLED, install(temp, target, opposite))
        assertEquals("NEW", target.readText())
    }

    @Test
    fun `failed durable preference commit rolls the same-kind file swap back`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")

        val outcome = install(temp, target, opposite, onCommit = { false })

        assertEquals(HeroInstallOutcome.PERSIST_FAILED, outcome)
        assertEquals("OLD", target.readText())
        assertFalse(File(folder.root, "hero.img.bak").exists())
    }

    @Test
    fun `failed rollback keeps the synced prior asset recoverable`() {
        val target = file("hero.img", "OLD")
        val temp = file("hero.img.part", "NEW")
        val opposite = File(folder.root, "hero.mp4")
        var moveCalls = 0

        val outcome = install(
            temp = temp,
            target = target,
            opposite = opposite,
            move = { from, to ->
                moveCalls++
                if (moveCalls == 1) {
                    Files.move(
                        from.toPath(),
                        to.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                } else {
                    false
                }
            },
            onCommit = { false },
        )

        assertEquals(HeroInstallOutcome.ROLLBACK_FAILED, outcome)
        assertEquals("NEW", target.readText())
        assertEquals("OLD", File(folder.root, "hero.img.bak").readText())
    }

    @Test
    fun `sweep keeps every active user hero and removes only scratch or orphan files`() {
        val active = (1..30).mapTo(linkedSetOf()) { "active-$it.img" }
        active.forEach { file(it, "ACTIVE") }
        val recoverable = file("active-1.img.bak", "PRIOR")
        val orphanedBackup = file("missing.img.bak", "STALE")
        val orphan = file("orphan.mp4", "ORPHAN")
        val scratch = file("failed.img.part", "PART")
        val unrelated = file("notes.txt", "KEEP")

        sweepHeroDirectory(folder.root, active)

        assertTrue(active.all { File(folder.root, it).isFile })
        assertTrue("an active identity's rollback copy must survive unrelated work", recoverable.isFile)
        assertFalse(orphanedBackup.exists())
        assertFalse(orphan.exists())
        assertFalse(scratch.exists())
        assertTrue(unrelated.exists())
    }
}
