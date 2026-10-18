package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of Mimir's string resources (localization plan, decisions 1 and 3): the default `values/` stays English, `values-b+es+419`
 * carries the Latin American Spanish, and `values-es` is a byte-for-byte copy of it for a device set to plain `es`. Every module that has
 * strings must have all three, with the same names and the same placeholders, so a string added in English cannot ship untranslated and
 * a Spanish fix cannot reach only one of the two folders. Read from source, relative to this module, like the other source pins.
 */
class LocalizationResourcesTest {

    /** Every module with Mimir-owned strings. Add a module here when it gets its first `strings.xml`. */
    private val modules = listOf(".", "../feature-settings", "../core-ui", "../feature-library", "../feature-files", "../core-companion-native")

    private val stringRe = Regex("""<string\s+name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val pluralsRe = Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val itemRe = Regex("""<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholderRe = Regex("""%(\d+\$)?[sdf]""")

    private fun res(module: String, folder: String): File = File("$module/src/main/res/$folder/strings.xml")

    /** The text of every `<string>`, by name. */
    private fun strings(file: File): Map<String, String> =
        stringRe.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[3].trim() }

    /** Strings marked `translatable="false"`: brand wordmarks. They live in the default folder only. */
    private fun untranslatable(file: File): Set<String> =
        stringRe.findAll(file.readText()).filter { it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }.toSet()

    /** The items of every `<plurals>`, by name then quantity. */
    private fun plurals(file: File): Map<String, Map<String, String>> =
        pluralsRe.findAll(file.readText()).associate { plural ->
            plural.groupValues[1] to itemRe.findAll(plural.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
        }

    private fun placeholders(text: String): List<String> = placeholderRe.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every module with strings has the default and both Spanish folders`() {
        modules.forEach { module ->
            listOf("values", "values-b+es+419", "values-es").forEach { folder ->
                assertTrue("$module is missing ${res(module, folder)}", res(module, folder).isFile)
            }
        }
    }

    @Test
    fun `values-es is a byte for byte copy of values-b+es+419`() {
        modules.forEach { module ->
            assertEquals(
                "$module: values-es must be an exact copy of values-b+es+419",
                res(module, "values-b+es+419").readText(),
                res(module, "values-es").readText(),
            )
        }
    }

    @Test
    fun `the Spanish folders translate exactly the strings the default has`() {
        modules.forEach { module ->
            val englishFile = res(module, "values")
            val english = strings(englishFile)
            val spanish = strings(res(module, "values-b+es+419"))
            val brand = untranslatable(englishFile)
            assertTrue("$module has no default strings", english.isNotEmpty())
            assertEquals("$module: Spanish and English must name the same strings", english.keys - brand, spanish.keys)
            assertTrue("$module: a wordmark is not translated, so Spanish must not carry it", spanish.keys.intersect(brand).isEmpty())
            spanish.forEach { (name, value) -> assertTrue("$module: $name is empty in Spanish", value.isNotBlank()) }
        }
    }

    @Test
    fun `a translation keeps the placeholders of its English string`() {
        modules.forEach { module ->
            val english = strings(res(module, "values"))
            val spanish = strings(res(module, "values-b+es+419"))
            english.filterKeys { it in spanish }.forEach { (name, value) ->
                assertEquals("$module: $name must keep its placeholders", placeholders(value), placeholders(spanish.getValue(name)))
            }
        }
    }

    @Test
    fun `plurals exist in both languages, each with an other form and the English placeholders`() {
        modules.forEach { module ->
            val english = plurals(res(module, "values"))
            val spanish = plurals(res(module, "values-b+es+419"))
            assertEquals("$module: Spanish and English must name the same plurals", english.keys, spanish.keys)
            english.forEach { (name, items) ->
                val other = placeholders(items.getValue("other"))
                items.forEach { (quantity, text) ->
                    assertEquals("$module: $name/$quantity (English) must share its other form's placeholders", other, placeholders(text))
                }
                val translated = spanish.getValue(name)
                assertTrue("$module: $name has no other form in Spanish", "other" in translated)
                translated.forEach { (quantity, text) ->
                    assertEquals("$module: $name/$quantity (Spanish) must keep the placeholders", other, placeholders(text))
                    assertTrue("$module: $name/$quantity is empty in Spanish", text.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `every string or plural the code asks for exists in the default folder`() {
        val known = modules.flatMap { module ->
            strings(res(module, "values")).keys + plurals(res(module, "values")).keys
        }.toSet()
        val reference = Regex("""\bR\.(?:string|plurals)\.([A-Za-z0-9_]+)""")
        val missing = modules.flatMap { module ->
            File("$module/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                reference.findAll(file.readText()).map { it.groupValues[1] to file.name }.toList()
            }.toList()
        }.filter { (name, _) -> name !in known }
        assertTrue("these resources are used but not defined: $missing", missing.isEmpty())
    }

    @Test
    fun `the first-run strings follow the module_screen_purpose naming`() {
        val names = strings(res(".", "values")).keys + plurals(res(".", "values")).keys
        val unnamed = names.filterNot { it.startsWith("mimir_") || it.startsWith("app_") }
        assertTrue("app strings must start with app_ (or be one of the system labels): $unnamed", unnamed.isEmpty())
    }

    @Test
    fun `interface strings are stored in sentence case, with no all-caps word`() {
        // Capitalization contract: caps only for controller tokens and call numbers, and nothing uppercases in code. A word of
        // three capitals or more is how a caps-stored string shows up; A, B, X, Y, M, L1, R1 and L3 are shorter, and the longer
        // controller tokens (es-419 glossary) are stripped first. GNU is the one name allowed besides: it is part of the legal name of
        // the license ("GNU General Public License") that Settings > About must show, and it is never a shouted word.
        val controllerTokens = Regex("""\b(START|SELECT|D-PAD|GNU)\b""")
        val shouting = Regex("""[A-ZÁÉÍÓÚÑ]{3,}""")
        modules.forEach { module ->
            listOf("values", "values-b+es+419").forEach { folder ->
                strings(res(module, folder)).forEach { (name, value) ->
                    assertTrue("$module/$folder: $name is stored in caps: $value", !shouting.containsMatchIn(controllerTokens.replace(value, "")))
                }
            }
        }
    }

    @Test
    fun `the default stays English`() {
        // The one string every player meets before choosing anything; the default folder must never be the Spanish.
        assertEquals("Language", strings(res("../feature-settings", "values")).getValue("settings_language_title"))
        assertEquals("Mimir Keyboard", strings(res(".", "values")).getValue("mimir_ime_label"))
        assertEquals("Begin", strings(res(".", "values")).getValue("app_onboarding_welcome_begin"))
        assertEquals("Comenzar", strings(res(".", "values-b+es+419")).getValue("app_onboarding_welcome_begin"))
    }
}
