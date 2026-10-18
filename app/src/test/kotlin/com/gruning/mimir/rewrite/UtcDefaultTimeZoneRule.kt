package com.gruning.mimir.rewrite

import java.util.TimeZone
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Pins the JVM's default time zone to UTC for one test. The Bottom prints an unlock's date in the device's own zone, so a test that pins the printed text needs the zone
 * fixed or it would read differently on every machine.
 */
class UtcDefaultTimeZoneRule : TestWatcher() {
    private var saved: TimeZone? = null

    override fun starting(description: Description) {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    override fun finished(description: Description) {
        saved?.let { TimeZone.setDefault(it) }
    }
}
