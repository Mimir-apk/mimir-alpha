package com.gruning.mimir.tweaker

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ManifestControlPermissionContractTest {
    @Test
    fun `product manifest advertises the exact Binder API`() {
        val buildScript = buildFile().readText()
        val advertised = Regex("manifestPlaceholders\\[\"mimirTweakerApiVersion\"\\] = \"([0-9]+)\"")
            .find(buildScript)
            ?.groupValues
            ?.get(1)
            ?.toInt()

        assertEquals(MimirTweakerService.API_VERSION, advertised)
    }

    @Test
    fun `Tweaker requests its signature control permission and protects its service`() {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)

        val controlPermission = "com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER"
        val declaredPermission = elements(document, "permission").single {
            it.getAttributeNS(androidNamespace, "name") == controlPermission
        }
        assertEquals("signature", declaredPermission.getAttributeNS(androidNamespace, "protectionLevel"))
        assertTrue(
            elements(document, "uses-permission").any {
                it.getAttributeNS(androidNamespace, "name") == controlPermission
            },
        )

        val service = elements(document, "service").single {
            it.getAttributeNS(androidNamespace, "name") == ".MimirTweakerService"
        }
        assertEquals(controlPermission, service.getAttributeNS(androidNamespace, "permission"))
    }

    private fun elements(document: org.w3c.dom.Document, tagName: String): List<Element> =
        (0 until document.getElementsByTagName(tagName).length).map {
            document.getElementsByTagName(tagName).item(it) as Element
        }

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

    private fun buildFile(): File = generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
        .flatMap { root ->
            sequenceOf(
                File(root, "build.gradle.kts"),
                File(root, "app/build.gradle.kts"),
                File(root, "mimir-tweaker/app/build.gradle.kts"),
            )
        }
        .firstOrNull { file ->
            file.isFile && file.readText().contains("mimirTweakerApiVersion")
        }
        ?: error("Unable to locate mimir-tweaker app build.gradle.kts")
}
