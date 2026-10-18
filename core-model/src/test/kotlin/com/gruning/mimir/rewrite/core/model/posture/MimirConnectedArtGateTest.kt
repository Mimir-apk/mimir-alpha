package com.gruning.mimir.rewrite.core.model.posture

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirConnectedArtGateTest {

    @After
    fun reset() {
        // The gate is a process singleton; leave it CLOSED for other tests / real launches.
        MimirConnectedArtGate.setOpen(false)
    }

    @Test
    fun closedByDefault_blocksEveryHost() {
        assertFalse(MimirConnectedArtGate.isOpen)
        assertFalse(MimirConnectedArtGate.allowsRequestTo("www.screenscraper.fr"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo("api.screenscraper.fr"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo("example.com"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo(null))
    }

    @Test
    fun open_allowsOnlyScreenScraperHosts() {
        MimirConnectedArtGate.setOpen(true)
        assertTrue(MimirConnectedArtGate.allowsRequestTo("screenscraper.fr"))
        assertTrue(MimirConnectedArtGate.allowsRequestTo("www.screenscraper.fr"))
        assertTrue(MimirConnectedArtGate.allowsRequestTo("api.screenscraper.fr"))
        assertTrue(MimirConnectedArtGate.allowsRequestTo("MEDIA.screenscraper.fr")) // subdomain + case
        // Even when open, every non-allowlisted host is still refused.
        assertFalse(MimirConnectedArtGate.allowsRequestTo("example.com"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo("evil-screenscraper.fr.attacker.com"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo("screenscraper.fr.evil.com"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo(null))
        assertFalse(MimirConnectedArtGate.allowsRequestTo(""))
    }

    @Test
    fun hostAllowlistIsIndependentOfOpenState() {
        // isAllowedHost reports membership regardless of the gate; allowsRequestTo gates on both.
        assertTrue(MimirConnectedArtGate.isAllowedHost("screenscraper.fr"))
        assertFalse(MimirConnectedArtGate.isAllowedHost("example.com"))
        assertFalse(MimirConnectedArtGate.allowsRequestTo("screenscraper.fr")) // closed → still denied
    }
}
