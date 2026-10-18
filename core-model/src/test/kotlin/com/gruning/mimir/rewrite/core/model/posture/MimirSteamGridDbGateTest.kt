package com.gruning.mimir.rewrite.core.model.posture

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirSteamGridDbGateTest {

    @After
    fun reset() {
        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(false)
    }

    @Test
    fun closedByDefault_blocksEveryHost() {
        assertFalse(MimirSteamGridDbGate.isOpen)
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("www.steamgriddb.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("cdn2.steamgriddb.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("example.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo(null))
    }

    @Test
    fun open_allowsOnlySteamGridDbHosts() {
        MimirSteamGridDbGate.setOpen(true)
        assertTrue(MimirSteamGridDbGate.allowsRequestTo("steamgriddb.com"))
        assertTrue(MimirSteamGridDbGate.allowsRequestTo("www.steamgriddb.com"))
        assertTrue(MimirSteamGridDbGate.allowsRequestTo("cdn2.steamgriddb.com"))
        assertTrue(MimirSteamGridDbGate.allowsRequestTo("MEDIA.CDN2.steamgriddb.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("example.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("evil-steamgriddb.com.attacker.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("steamgriddb.com.evil.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo(null))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo(""))
    }

    @Test
    fun hostAllowlistIsIndependentOfOpenState() {
        assertTrue(MimirSteamGridDbGate.isAllowedHost("www.steamgriddb.com"))
        assertFalse(MimirSteamGridDbGate.isAllowedHost("example.com"))
        assertFalse(MimirSteamGridDbGate.allowsRequestTo("www.steamgriddb.com"))
    }

    @Test
    fun toggleIsIndependentFromMimirConnectedArtGate() {
        MimirSteamGridDbGate.setOpen(true)
        assertFalse(MimirConnectedArtGate.isOpen)
        assertTrue(MimirSteamGridDbGate.isOpen)

        MimirConnectedArtGate.setOpen(true)
        assertTrue(MimirConnectedArtGate.isOpen)
        assertTrue(MimirSteamGridDbGate.isOpen)

        MimirConnectedArtGate.setOpen(false)
        assertTrue(MimirSteamGridDbGate.isOpen)
        assertFalse(MimirConnectedArtGate.isOpen)

        MimirSteamGridDbGate.setOpen(false)
        assertFalse(MimirSteamGridDbGate.isOpen)
        assertFalse(MimirConnectedArtGate.isOpen)
    }
}
