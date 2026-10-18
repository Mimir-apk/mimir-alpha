package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerRoutingPolicyTest {

    @Test
    fun `every legacy and canonical optical platform rejects zip before open`() {
        assertEquals(24, ContainerRoutingPolicy.OPTICAL_PLATFORMS.size)
        ContainerRoutingPolicy.OPTICAL_PLATFORMS.forEach { platform ->
            assertEquals(
                platform,
                ContainerRoutingPolicy.Route.OPTICAL_ZIP_UNSUPPORTED,
                ContainerRoutingPolicy.route(platform, "disc.zip"),
            )
        }
        assertEquals(
            ContainerRoutingPolicy.Route.DEFAULT,
            ContainerRoutingPolicy.route("gba", "cartridge.zip"),
        )
    }

    @Test
    fun `scanner filename outranks opaque SAF document id`() {
        val opaque = "content://provider/document/42"
        val chd = ContainerRoutingPolicy.fileName("PS1/disc.chd", "disc", opaque)
        val nsp = ContainerRoutingPolicy.fileName("Switch/base.nsp", "base", opaque)
        val opticalZip = ContainerRoutingPolicy.fileName("Wii/disc.zip", "disc", opaque)
        val opaqueRelative = ContainerRoutingPolicy.fileName("42", "fallback-disc.chd", opaque)

        assertEquals(ContainerRoutingPolicy.Route.CHD, ContainerRoutingPolicy.route("ps1", chd))
        assertEquals(
            ContainerRoutingPolicy.Route.SWITCH_NSP_PROVISIONAL,
            ContainerRoutingPolicy.route("switch", nsp),
        )
        assertEquals(
            ContainerRoutingPolicy.Route.OPTICAL_ZIP_UNSUPPORTED,
            ContainerRoutingPolicy.route("wii", opticalZip),
        )
        assertEquals(
            ContainerRoutingPolicy.Route.CHD,
            ContainerRoutingPolicy.route("ps1", opaqueRelative),
        )
    }
}
