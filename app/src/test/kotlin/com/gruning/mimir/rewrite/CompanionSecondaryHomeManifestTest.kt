package com.gruning.mimir.rewrite

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Pins the one manifest attribute that decides whether Android will ever place Mimir's secondary-home
 * floor on the Thor's second display.
 *
 * `RootWindowContainer.canStartHomeOnDisplayArea` (Android 13, `android-13.0.0_r1`) refuses a home
 * activity on any non-default display when its `launchMode` is `singleTask` or `singleInstance`:
 * *"Can't launch home on secondary displays if it requested to be single instance."* The floor shipped
 * as `singleTask`, so `resolveSecondaryHomeActivity` found it in the default-home package, rejected it,
 * and fell back to `config_secondaryHomePackage` = launcher3. That is exactly what the Thor showed for a
 * week: `SecondaryDisplayLauncher` resumed on display 4 and `MimirSecondaryHome` never logged once
 * . launcher3's
 * own `SecondaryDisplayLauncher` declares `singleTop` — read off the Thor's `Launcher3QuickStepEX6.apk`
 * (`launchMode=1`), not off a document.
 */
class CompanionSecondaryHomeManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun secondaryHomeFloor(): Element {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val floor = (0 until activities.length)
            .map { activities.item(it) as Element }
            .firstOrNull { it.getAttributeNS(android, "name").endsWith("CompanionSecondaryHomeActivity") }
        return requireNotNull(floor) { "CompanionSecondaryHomeActivity is not declared in the manifest" }
    }

    private fun manifest(): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun activityNamed(document: Document, simpleName: String): Element {
        val activities = document.getElementsByTagName("activity")
        val found = (0 until activities.length)
            .map { activities.item(it) as Element }
            .firstOrNull { it.getAttributeNS(android, "name").endsWith(".$simpleName") }
        return requireNotNull(found) { "$simpleName is not declared in the manifest" }
    }

    /**
     * The affinity Android 13's package parser gives [activity] (`ParsedActivityUtils` and `ParsingPackageUtils`, both
     * through `ComponentParseUtils.buildTaskAffinityName`): the activity's own `android:taskAffinity`, else the
     * application's, else the package name. An empty value means no affinity (null), and a value starting with ':' is
     * appended to the package name. `${applicationId}` is filled in as the manifest merger does.
     */
    private fun parsedTaskAffinity(application: Element, activity: Element, packageName: String): String? {
        fun declared(element: Element): String? =
            if (element.hasAttributeNS(android, "taskAffinity")) element.getAttributeNS(android, "taskAffinity") else null
        fun build(default: String?, raw: String?): String? {
            val value = raw?.replace("${'$'}{applicationId}", packageName) ?: return default
            return when {
                value.isEmpty() -> null
                value.startsWith(":") -> packageName + value
                else -> value
            }
        }
        return build(build(packageName, declared(application)), declared(activity))
    }

    @Test
    fun secondaryHomeFloorDeclaresTheSecondaryHomeCategory() {
        val categories = secondaryHomeFloor().getElementsByTagName("category")
        val names = (0 until categories.length).map { (categories.item(it) as Element).getAttributeNS(android, "name") }
        assertTrue("SECONDARY_HOME category missing from the floor: $names", "android.intent.category.SECONDARY_HOME" in names)
    }

    /**
     * Measured on the Thor 2026-09-06 : on 2 of 5 wakes the
     * platform pushes a transient 320 → 369 dpi config on display 4 ten milliseconds apart. A floor that does not
     * handle density and the screen-size set is relaunched for it (twice on a lid wake) and WindowManager freezes
     * display 4 for ~400 ms — "Screen frozen for +404ms due to Window{…CompanionSecondaryHomeActivity}". MainActivity
     * already declares this set and did not relaunch.
     */
    @Test
    fun secondaryHomeFloorHandlesTheWakeTimeConfigTransientItself() {
        val declared = secondaryHomeFloor().getAttributeNS(android, "configChanges").split('|').filter { it.isNotBlank() }.toSet()
        val required = setOf("density", "screenSize", "smallestScreenSize", "screenLayout", "orientation")
        val missing = required - declared
        assertTrue(
            "CompanionSecondaryHomeActivity configChanges=$declared is missing $missing: without them a wake-time " +
                "density/size transient on display 4 relaunches the floor and freezes the second screen for ~0.4 s.",
            missing.isEmpty(),
        )
    }

    /**
     * Localization step 0 (2026-10-17): from API 33 a change of Mimir's language goes through LocaleManager, which relaunches
     * every window that does not handle locale itself. The floor draws no text, so it handles the change and display 4 is
     * never relaunched for it.
     */
    @Test
    fun secondaryHomeFloorHandlesALanguageChangeItself() {
        val declared = secondaryHomeFloor().getAttributeNS(android, "configChanges").split('|').filter { it.isNotBlank() }.toSet()
        val missing = setOf("locale", "layoutDirection") - declared
        assertTrue(
            "CompanionSecondaryHomeActivity configChanges=$declared is missing $missing: a language change would relaunch the floor on display 4.",
            missing.isEmpty(),
        )
    }

    @Test
    fun secondaryHomeFloorIsNeitherSingleTaskNorSingleInstance() {
        val launchMode = secondaryHomeFloor().getAttributeNS(android, "launchMode")
        assertTrue(
            "CompanionSecondaryHomeActivity launchMode=\"$launchMode\": RootWindowContainer.canStartHomeOnDisplayArea " +
                "refuses a singleTask/singleInstance home on a secondary display, so Android falls back to launcher3's " +
                "SecondaryDisplayLauncher and Mimir's floor never starts. Declare singleTop, as launcher3 does.",
            launchMode != "singleTask" && launchMode != "singleInstance",
        )
    }

    /**
     * N1, Thor 2026-09-11 . Adding a task
     * to recents makes Android 13's `RecentTasks.removeForAddTask` take out a task of a compatible activity type and
     * windowing mode whose affinity matches (`findRemoveIndexForAddTask`), and put it on the hidden list if it still has
     * activities. When a home activity goes idle, `onActivityIdle` removes every hidden task with no visible activity
     * (`remove-hidden-task`). MainActivity and this floor are both home tasks, so on the shared package affinity each hid
     * the other, and a SWAP to Main or a reclaim with a game on Main destroyed MainActivity under the covering app.
     * A null affinity never matches, so that passes too.
     */
    @Test
    fun secondaryHomeFloorNeverSharesMainActivityTaskAffinity() {
        val document = manifest()
        val application = document.getElementsByTagName("application").item(0) as Element
        val packageName = BuildConfig.APPLICATION_ID
        val main = parsedTaskAffinity(application, activityNamed(document, "MainActivity"), packageName)
        val floor = parsedTaskAffinity(application, activityNamed(document, "CompanionSecondaryHomeActivity"), packageName)
        assertTrue(
            "CompanionSecondaryHomeActivity taskAffinity=$floor matches MainActivity's ($main). Android 13 RecentTasks hides " +
                "one home task whenever the other is added, then removes it when a home activity goes idle while an app " +
                "covers it. Give the floor its own android:taskAffinity.",
            floor == null || floor != main,
        )
    }
}
