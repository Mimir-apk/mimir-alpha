package com.gruning.mimir.rewrite.core.launch

/** Android's `UserHandle.PER_USER_RANGE`: every user owns a block of 100000 uids, so `uid / 100000` is the user. */
private const val PER_USER_RANGE = 100_000

/**
 * The Android user a process with [uid] runs as: 0 for the owner, 10 and up for a secondary user or a work profile.
 *
 * Taken from the uid and not from `Context.dataDir` on purpose. An app installed on adopted storage keeps its data under
 * `/mnt/expand/<volume>/user/<id>/`, which no `/data/user/<id>/` pattern would read, and the answer for those would
 * silently fall back to the player's user, which is the very mistake this exists to remove. A negative uid (an
 * unresolved process, or a unit-test stub that returns the default) is the owner.
 */
internal fun androidUserIdOf(uid: Int): Int = if (uid < 0) 0 else uid / PER_USER_RANGE

/**
 * The root of the primary shared storage the Android user [userId] sees: `/storage/emulated/<id>`. The id is part of the
 * path (the owner is `0`, a secondary user or a work profile is `10` and up), so a path spelled with a bare `0` names the
 * owner's files and nobody else's. Every BIOS and emulator-data location that lives on primary storage is built from this.
 */
internal fun primaryStorageRootFor(userId: Int): String = "/storage/emulated/$userId"
