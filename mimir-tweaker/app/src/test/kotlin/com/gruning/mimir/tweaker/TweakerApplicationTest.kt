package com.gruning.mimir.tweaker

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The process gate, the poller schedule, and the compiled facts the live family-catalog refresh stands on. */
class TweakerApplicationTest {
    private val pkg = "com.gruning.mimir.tweaker"

    @Test
    fun `only the main process fetches`() {
        assertTrue(FamilyCatalogProcess.fetchesIn(pkg, pkg))
        assertFalse(FamilyCatalogProcess.fetchesIn("$pkg:guardian", pkg))
        assertFalse(FamilyCatalogProcess.fetchesIn(":guardian", pkg))
        assertFalse(FamilyCatalogProcess.fetchesIn("com.other.app", pkg))
        assertFalse(FamilyCatalogProcess.fetchesIn("", pkg))
        assertFalse(FamilyCatalogProcess.fetchesIn(null, pkg))
    }

    @Test
    fun `the poller schedules once, 10 s in and then every 24 h`() {
        val scheduled = mutableListOf<Triple<Runnable, Long, Long>>()
        var runs = 0
        val poller = FamilyCatalogPoller(task = { runs++ }, schedule = { r, first, every -> scheduled += Triple(r, first, every) })

        assertEquals(0, scheduled.size)
        assertTrue(poller.start())
        assertFalse(poller.start())

        assertEquals(1, scheduled.size)
        assertEquals(10_000L, scheduled.single().second)
        assertEquals(24L * 60 * 60 * 1000, scheduled.single().third)
        assertTrue("never earlier than 10 s", scheduled.single().second >= 10_000L)
        assertEquals(0, runs)
        scheduled.single().first.run()
        scheduled.single().first.run()
        assertEquals(2, runs)
    }

    @Test
    fun `a task that throws neither escapes nor stops the next run`() {
        var runs = 0
        val scheduled = mutableListOf<Runnable>()
        val poller = FamilyCatalogPoller(
            task = {
                runs++
                if (runs == 1) throw IllegalStateException("boom") else throw LinkageError("missing class")
            },
            schedule = { r, _, _ -> scheduled += r },
        )
        poller.start()
        scheduled.single().run()
        scheduled.single().run()
        assertEquals(2, runs)
    }

    @Test
    fun `the daemon thread poller creates nothing until it is started`() {
        val before = Thread.getAllStackTraces().keys.count { it.name == "mimir-family-catalog" }
        FamilyCatalogPoller.onDaemonThread { }
        assertEquals(before, Thread.getAllStackTraces().keys.count { it.name == "mimir-family-catalog" })
    }

    @Test
    fun `the compiled catalog URL points at one raw host and the build type picks the branch`() {
        val url = BuildConfig.FAMILY_CATALOG_URL
        val branch = if (BuildConfig.DEBUG) "staging" else "main"
        assertEquals("https://raw.githubusercontent.com/Mimir-apk/tweaker-families/$branch/tweaker-families.json", url)
        assertEquals("$url.sig", FamilyCatalogFetcher.signatureUrlFor(url))
    }

    @Test
    fun `the compiled public key is empty (fetch off) or exactly a 32 byte Ed25519 key`() {
        val key = BuildConfig.FAMILY_CATALOG_PUBLIC_KEY_BASE64
        assertTrue(key.isEmpty() || FamilyCatalogSignature.decodePublicKey(key) != null)
    }

    @Test
    fun `the manifest requests INTERNET, names the application, and keeps the guardian in its own process`() {
        val ns = "http://schemas.android.com/apk/res/android"
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestFile())

        val permissions = elements(document, "uses-permission").map { it.getAttributeNS(ns, "name") }
        assertTrue(permissions.contains("android.permission.INTERNET"))

        val application = elements(document, "application").single()
        assertEquals(".TweakerApplication", application.getAttributeNS(ns, "name"))

        val guardian = elements(document, "service").single { it.getAttributeNS(ns, "name") == ".RestoreGuardianProcessService" }
        assertEquals(":guardian", guardian.getAttributeNS(ns, "process"))
        assertNotNull(Class.forName("com.gruning.mimir.tweaker.TweakerApplication"))
    }

    private fun elements(document: org.w3c.dom.Document, tagName: String): List<Element> =
        (0 until document.getElementsByTagName(tagName).length).map { document.getElementsByTagName(tagName).item(it) as Element }

    private fun manifestFile(): File = generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
        .flatMap { root ->
            sequenceOf(
                File(root, "src/main/AndroidManifest.xml"),
                File(root, "app/src/main/AndroidManifest.xml"),
                File(root, "mimir-tweaker/app/src/main/AndroidManifest.xml"),
            )
        }
        .firstOrNull(File::isFile)
        ?: error("Unable to locate mimir-tweaker AndroidManifest.xml")
}
