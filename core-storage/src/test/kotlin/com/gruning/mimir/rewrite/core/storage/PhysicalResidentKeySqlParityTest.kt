package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalResidentKeySqlParityTest {

    @Test
    fun `sqlite migration expression matches kotlin codec over adversarial unicode corpus`() {
        val roots = listOf(
            "/storage/roms",
            "/storage/roms:",
            "content://com.android.externalstorage.documents/tree/691E%3A7BEA",
            "p2:4:root",
            "A😀e\u0301",
            "שלום/ゲーム",
            "𠀀-root",
        )
        val relativePaths = listOf(
            "game.nes",
            ":game",
            "folder::game.chd",
            "100%/literal%3Aname.rom",
            "😀/e\u0301/ゲーム.rom",
            "𠀀/مرحبا.rom",
            " ",
        )
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.prepareStatement(
                "SELECT 'p2:' || LENGTH(?) || ':' || LENGTH(?) || ':' || ? || ?",
            ).use { statement ->
                roots.forEach { root ->
                    relativePaths.forEach { relativePath ->
                        statement.setString(1, root)
                        statement.setString(2, relativePath)
                        statement.setString(3, root)
                        statement.setString(4, relativePath)
                        statement.executeQuery().use { result ->
                            result.next()
                            assertEquals(
                                "root=$root relative=$relativePath",
                                physicalResidentKey(root, relativePath),
                                result.getString(1),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `sqlite text length agrees with kotlin code point count and not utf16 length`() {
        val value = "A😀e\u0301"
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.prepareStatement("SELECT LENGTH(?)").use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { result ->
                    result.next()
                    assertEquals(value.codePointCount(0, value.length), result.getInt(1))
                    assertEquals(4, result.getInt(1))
                    assertEquals(5, value.length)
                }
            }
        }
    }
}
