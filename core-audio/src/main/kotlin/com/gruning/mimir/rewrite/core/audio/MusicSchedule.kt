package com.gruning.mimir.rewrite.core.audio

import java.time.Instant
import java.time.ZoneId

/**
 * The hours of the day the music may play in (Settings › Music › Music hours). The schedule uses
 * a short list of named windows rather than a two-time editor: it needs no new input grammar,
 * and the shared choice-row shell gives it pad and touch access. The choice is stored as the preset's [name].
 *
 * [startMinute] and [endMinute] are minutes after midnight, the window being [startMinute] up to but not including [endMinute]
 * ([MusicSchedulePolicy] also reads a window that runs through midnight, though no preset does yet). [ALL_DAY] has no window: the
 * music is never held for the time of day.
 */
enum class MimirMusicHours(val startMinute: Int, val endMinute: Int) {
    ALL_DAY(0, 0),
    DAYTIME(7 * 60, 19 * 60),
    EVENING(17 * 60, 23 * 60),
    NOT_OVERNIGHT(7 * 60, 23 * 60),
    ;

    /** True when the music is held for some part of the day. */
    val limited: Boolean get() = this != ALL_DAY
}

/** Pure rules for the music hours. [MimirMusicControl] feeds the wall clock through them; nothing here reads a clock. */
internal object MusicSchedulePolicy {
    private const val MinutesPerDay = 24 * 60
    private const val MillisPerMinute = 60_000L
    const val MillisPerDay = MinutesPerDay * MillisPerMinute

    /**
     * Whether [minuteOfDay] (0..1439, wrapped if outside) is inside the window [startMinute] up to [endMinute]. A window that ends
     * before it starts runs through midnight.
     */
    fun isOpen(startMinute: Int, endMinute: Int, minuteOfDay: Int): Boolean {
        val minute = minuteOfDay.mod(MinutesPerDay)
        return if (startMinute < endMinute) {
            minute >= startMinute && minute < endMinute
        } else {
            minute >= startMinute || minute < endMinute
        }
    }

    /** Whether the music may play at [minuteOfDay]. [MimirMusicHours.ALL_DAY] is always open. */
    fun isOpen(hours: MimirMusicHours, minuteOfDay: Int): Boolean =
        !hours.limited || isOpen(hours.startMinute, hours.endMinute, minuteOfDay)

    /**
     * Milliseconds from [millisIntoDay] (wrapped into 0 until [MillisPerDay]) to the next moment the answer of [isOpen] turns over: the
     * window's end while it is open, its start while it is closed.
     */
    fun millisUntilChange(startMinute: Int, endMinute: Int, millisIntoDay: Long): Long {
        val now = millisIntoDay.mod(MillisPerDay)
        val open = isOpen(startMinute, endMinute, (now / MillisPerMinute).toInt())
        val boundary = (if (open) endMinute else startMinute) * MillisPerMinute
        val until = (boundary - now).mod(MillisPerDay)
        return if (until == 0L) MillisPerDay else until
    }

    /** As [millisUntilChange] for a preset; null for [MimirMusicHours.ALL_DAY], which never turns over. */
    fun millisUntilChange(hours: MimirMusicHours, millisIntoDay: Long): Long? =
        if (hours.limited) millisUntilChange(hours.startMinute, hours.endMinute, millisIntoDay) else null

    /** The minute of the day (0..1439) at [epochMs] on the wall clock of [zone]. */
    fun minuteOfDay(epochMs: Long, zone: ZoneId): Int {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        return local.hour * 60 + local.minute
    }

    /**
     * The instant (epoch milliseconds) of the next edge of [hours] after [nowEpochMs], read on the wall clock of [zone]: the window's end
     * while it is open, its start while it is closed. Null for [MimirMusicHours.ALL_DAY]. It is an instant and not a length of time, so
     * a day of 23 or 25 hours (a clock change) puts the edge where the wall clock does, not an hour off.
     */
    fun nextEdgeEpochMs(hours: MimirMusicHours, nowEpochMs: Long, zone: ZoneId): Long? {
        if (!hours.limited) return null
        val local = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val open = isOpen(hours, local.hour * 60 + local.minute)
        val boundary = (if (open) hours.endMinute else hours.startMinute).mod(MinutesPerDay)
        val today = local.toLocalDate().atTime(boundary / 60, boundary % 60)
        val edge = today.atZone(zone).toInstant().toEpochMilli()
        return if (edge > nowEpochMs) edge else today.plusDays(1).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * The player pressed play during the off-hours, which clears the hold: from [nowEpochMs] until the next edge the music is theirs. The
     * end is an absolute instant, so a clock that jumps (set by hand, a new time zone, a day slept through) cannot leave the override
     * standing into a later night.
     */
    fun overrideStarted(watch: MusicScheduleWatch, hours: MimirMusicHours, nowEpochMs: Long, zone: ZoneId): MusicScheduleWatch {
        val until = nextEdgeEpochMs(hours, nowEpochMs, zone) ?: return watch.copy(override = null)
        return watch.copy(override = MusicScheduleOverride(fromEpochMs = nowEpochMs, untilEpochMs = until))
    }

    /**
     * One look at the wall clock. The answer is [MusicScheduleLook.outsideHours]: true to hold the music for the off-hours, false to
     * release it, null to change nothing.
     *  - At an edge (the answer of [isOpen] differs from the last look) the hold follows the clock.
     *  - A live override (see [overrideStarted]) holds through the off-hours it was made in: nothing is set while it lasts.
     *  - An override that is past its instant, or that the clock has been set back before, is over: the schedule is applied afresh,
     *    whatever the last look said. This is what makes a clock that jumps from one night to the next hold the music again.
     *  - [force] (a different window was just chosen) applies the answer and ends any override.
     */
    fun look(watch: MusicScheduleWatch, hours: MimirMusicHours, nowEpochMs: Long, zone: ZoneId, force: Boolean): MusicScheduleLook {
        val open = isOpen(hours, minuteOfDay(nowEpochMs, zone))
        val override = watch.override
        val live = override?.holdsAt(nowEpochMs) == true
        val stillOverriding = live && !open && !force
        val outside = when {
            force -> !open
            override != null && !live -> !open
            live && open -> false
            stillOverriding -> null
            open != watch.lastOpen -> !open
            else -> null
        }
        return MusicScheduleLook(
            watch = MusicScheduleWatch(lastOpen = open, override = override.takeIf { stillOverriding }),
            outsideHours = outside,
        )
    }
}

/** The span of absolute instants a play during the off-hours holds for: [fromEpochMs] up to but not including [untilEpochMs]. */
internal data class MusicScheduleOverride(val fromEpochMs: Long, val untilEpochMs: Long) {
    fun holdsAt(nowEpochMs: Long): Boolean = nowEpochMs >= fromEpochMs && nowEpochMs < untilEpochMs
}

/** What the control remembers between looks at the clock (handler thread): the last answer, and the player's override, if any. */
internal data class MusicScheduleWatch(
    val lastOpen: Boolean? = null,
    val override: MusicScheduleOverride? = null,
)

/** The result of [MusicSchedulePolicy.look]: what to remember, and whether to set or clear the hold (null: leave it). */
internal data class MusicScheduleLook(val watch: MusicScheduleWatch, val outsideHours: Boolean?)
