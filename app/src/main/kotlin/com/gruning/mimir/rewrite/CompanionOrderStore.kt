package com.gruning.mimir.rewrite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The companion 3DS-shelf layout (HP-11 v2 S4) — Mimir's `Launcher.dat`.
 *
 * Three facts the user created with their own hands, and which must therefore outlive everything: the
 * ORDER they filed their tiles in, the tile their CURSOR rests on, and the PAGE they left open. The
 * Presentation is dismissed and rebuilt on every yield/reclaim, so `remember` inside the companion
 * composition cannot hold any of it — this object can, and SharedPreferences carries it across reboots.
 *
 * Order is a joined string, not a StringSet: [android.content.SharedPreferences] StringSets are unordered,
 * and order is the entire point. Ids are the tile ids — a platform id ("gba") or a package name — and a
 * unit-separator joins them, so neither can collide with the delimiter.
 *
 * Entries are NEVER pruned. A platform whose games all vanish leaves the grid (the shelf shows only
 * platforms with games) but keeps its slot here: when its games come back it returns to the exact cell the
 * user filed it in. Spatial memory survives the library breathing.
 */
/** A bound tome: a user-named grouping of console and app tiles (one level deep, like the 3DS). */
data class CompanionFolder(val id: String, val name: String, val children: List<String>)

/** Folder ids carry this prefix so a tile id can never be mistaken for a tome. */
const val FolderIdPrefix = "folder:"

/** The name a freshly bound tome wears until the user renames it. Use book language everywhere —
 *  never "Folder". "Book" matches the user-facing copy ("New book", "N books"); "tome" stays a code word. */
const val DefaultFolderName = "Book"

object CompanionOrderStore {
    private const val Prefs = "mimir_companion_shelf"
    private const val KeyOrder = "companionShelfOrder.v1"
    private const val KeyCursor = "companionShelfCursor.v1"
    private const val KeyPage = "companionShelfPage.v1"
    private const val KeyFolders = "companionShelfFolders.v1"
    private const val KeyChildCursors = "companionShelfChildCursors.v1"
    private const val KeyActivitySwipeHintRetired = "companionActivitySwipeHintRetired.v1"
    private const val Separator = '\u001F'
    private const val RecordSeparator = '\u001E'
    private const val ChildSeparator = '\u001D'

    private val _order = MutableStateFlow<List<String>>(emptyList())

    /** The user's filed order. Empty = never reordered; the producer's default order stands. */
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private val _cursor = MutableStateFlow<String?>(null)

    /** The resting marker's tile — the bookmark. Null until the user touches something. */
    val cursor: StateFlow<String?> = _cursor.asStateFlow()

    private val _page = MutableStateFlow(0)

    /** The page the user left open. Restored by SNAP, never an animated glide (B1). */
    val page: StateFlow<Int> = _page.asStateFlow()

    private val _folders = MutableStateFlow<List<CompanionFolder>>(emptyList())

    /** The tomes the user bound with their own hands. */
    val folders: StateFlow<List<CompanionFolder>> = _folders.asStateFlow()

    private val _childCursors = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Last selected child per book; independent from the top-level shelf cursor. */
    val childCursors: StateFlow<Map<String, String>> = _childCursors.asStateFlow()

    private val _activitySwipeHintRetired = MutableStateFlow(false)

    /**
     * Whether the lattice footer's "Swipe right for Activity" hint is retired (B6): the player has made that swipe once, so the hint and the
     * height it asked for are gone for good. It lives in this store's prefs so it survives a restart; a data wipe brings the hint back.
     */
    val activitySwipeHintRetired: StateFlow<Boolean> = _activitySwipeHintRetired.asStateFlow()

    /** Read the layout once at startup, before the companion can compose. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _folders.value = decodeFolders(prefs.getString(KeyFolders, "").orEmpty())
        _order.value = prefs.getString(KeyOrder, "").orEmpty()
            .split(Separator)
            .filter { it.isNotBlank() }
        _cursor.value = prefs.getString(KeyCursor, null)?.takeIf { it.isNotBlank() }
        _page.value = prefs.getInt(KeyPage, 0).coerceAtLeast(0)
        _childCursors.value = decodeChildCursors(prefs.getString(KeyChildCursors, "").orEmpty())
        _activitySwipeHintRetired.value = prefs.getBoolean(KeyActivitySwipeHintRetired, false)
    }

    /**
     * Persist a drop. Retained-but-absent entries keep their EXACT filed cell (V8): the visible slots in the
     * stored order are refilled in-place with the new visible sequence, so a reorder never demotes a
     * temporarily-gone tile (games vanished) to the tail — its spatial memory survives (§ class doc). Brand-new
     * ids fall out the tail. [remove] drops single-use ids (a discarded folder id, V13) from the stored order
     * in the same atomic write; the default keeps every other caller behaviorally identical.
     */
    fun setOrder(context: Context, ids: List<String>, remove: Set<String> = emptySet()) {
        val visible = ids.toHashSet()
        val incoming = ArrayDeque(ids)
        val merged = _order.value
            .filterNot { it in remove }
            .map { if (it in visible) incoming.removeFirst() else it } + incoming
        _order.value = merged
        prefs(context).edit().putString(KeyOrder, merged.joinToString(Separator.toString())).apply()
    }

    fun setCursor(context: Context, id: String) {
        _cursor.value = id
        prefs(context).edit().putString(KeyCursor, id).apply()
    }

    fun setPage(context: Context, index: Int) {
        if (_page.value == index) return
        _page.value = index
        prefs(context).edit().putInt(KeyPage, index).apply()
    }

    /** Retires the Activity swipe hint for good (B6). Idempotent: the flag only ever goes true. */
    fun retireActivitySwipeHint(context: Context) {
        _activitySwipeHintRetired.value = true
        prefs(context).edit().putBoolean(KeyActivitySwipeHintRetired, true).apply()
    }

    fun setChildCursor(context: Context, folderId: String, childId: String) {
        val folder = _folders.value.firstOrNull { it.id == folderId } ?: return
        if (childId !in folder.children || _childCursors.value[folderId] == childId) return
        val updated = _childCursors.value + (folderId to childId)
        _childCursors.value = updated
        writeChildCursors(context, updated)
    }

    /**
     * Bind [draggedId] into the tome [targetId] — the ONLY way a tile joins a folder (CR-17).
     *
     * Dropping a tile onto another tile previously created a tome and could create unintended folders.
     * Creation is now
     * an explicit act ([createFolder]); a drop can only ever file a tile into a tome that already exists.
     *
     * [visibleOrder] is the order as the user currently sees it, so filing can't scramble a grid that was
     * never explicitly ordered.
     */
    fun bind(context: Context, visibleOrder: List<String>, draggedId: String, targetId: String) {
        if (draggedId == targetId) return
        if (draggedId.startsWith(FolderIdPrefix)) return // a tome never enters a tome (one level, like the 3DS)
        val folders = _folders.value.toMutableList()
        val targetIndex = folders.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return // not a tome: this is a reorder, not a bind
        val folder = folders[targetIndex]
        if (draggedId in folder.children) return
        folders[targetIndex] = folder.copy(children = folder.children + draggedId)
        // The filed tile leaves the top level.
        val order = visibleOrder.toMutableList().apply { remove(draggedId) }
        _folders.value = folders
        writeFolders(context, folders)
        setOrder(context, order)
    }

    /**
     *  move ONE child OUT of its book, back to the shelf — the long-press
     * menu INSIDE an open book. The child takes the cell right after its book, mirroring [discardFolder]'s
     * children-come-home placement, so nothing the user filed can ever be lost.
     */
    fun unbind(context: Context, visibleOrder: List<String>, childId: String) {
        val folderIndex = _folders.value.indexOfFirst { childId in it.children }
        if (folderIndex < 0) return
        val folders = _folders.value.toMutableList()
        val folder = folders[folderIndex]
        folders[folderIndex] = folder.copy(children = folder.children - childId)
        _folders.value = folders
        writeFolders(context, folders)
        if (_childCursors.value[folder.id] == childId) {
            val replacement = folders[folderIndex].children.firstOrNull()
            val updated = if (replacement == null) {
                _childCursors.value - folder.id
            } else {
                _childCursors.value + (folder.id to replacement)
            }
            _childCursors.value = updated
            writeChildCursors(context, updated)
        }
        val order = visibleOrder.toMutableList().apply { remove(childId) }
        val at = order.indexOf(folder.id)
        if (at >= 0) order.add(at + 1, childId) else order.add(childId)
        setOrder(context, order)
    }

    /**
     * Bind an EMPTY tome and give it the next free cell (CR-17). The user names it and fills it themselves —
     * the 3DS model, where creating a folder and filling it are two separate, deliberate acts.
     */
    fun createFolder(context: Context, visibleOrder: List<String>): String {
        // Every "+" makes a NEW Book. V9 used to reuse an un-renamed empty
        // Book so repeat taps would not litter duplicates; that made the second "+" only jump to the first one. A double-tap is the surface's to
        // debounce, not this store's to swallow. The id stays unique even where two calls read the same clock tick.
        var stamp = System.nanoTime()
        while (_folders.value.any { it.id == FolderIdPrefix + stamp }) stamp++
        val id = FolderIdPrefix + stamp
        val folders = _folders.value + CompanionFolder(id = id, name = DefaultFolderName, children = emptyList())
        _folders.value = folders
        writeFolders(context, folders)
        setOrder(context, visibleOrder + id)
        setCursor(context, id)
        return id
    }

    /**
     * Unbind a tome. Its children come home to the top level, taking the tome's own cell, so nothing the
     * user filed can ever be lost by discarding the thing that held it.
     */
    fun discardFolder(context: Context, visibleOrder: List<String>, folderId: String) {
        val folder = _folders.value.firstOrNull { it.id == folderId } ?: return
        val folders = _folders.value.filterNot { it.id == folderId }
        _folders.value = folders
        writeFolders(context, folders)
        if (folderId in _childCursors.value) {
            val updated = _childCursors.value - folderId
            _childCursors.value = updated
            writeChildCursors(context, updated)
        }
        val order = visibleOrder.toMutableList()
        val at = order.indexOf(folderId)
        if (at >= 0) {
            order.removeAt(at)
            order.addAll(at, folder.children)
        } else {
            order.addAll(folder.children)
        }
        // V13: the discarded folder id is single-use (folder:<nanoTime>, never reused) — drop it from the
        // STORED order too, or it accumulates in SharedPreferences forever.
        setOrder(context, order, remove = setOf(folderId))
    }

    fun renameFolder(context: Context, folderId: String, name: String) {
        val clean = name.trim().ifBlank { DefaultFolderName }
        val folders = _folders.value.map { if (it.id == folderId) it.copy(name = clean) else it }
        _folders.value = folders
        writeFolders(context, folders)
    }

    private fun writeFolders(context: Context, folders: List<CompanionFolder>) {
        prefs(context).edit().putString(KeyFolders, encodeFolders(folders)).apply()
    }

    private fun writeChildCursors(context: Context, cursors: Map<String, String>) {
        val encoded = cursors.entries.joinToString(RecordSeparator.toString()) { (folderId, childId) ->
            folderId + Separator + childId
        }
        prefs(context).edit().putString(KeyChildCursors, encoded).apply()
    }

    private fun encodeFolders(folders: List<CompanionFolder>): String =
        folders.joinToString(RecordSeparator.toString()) { folder ->
            listOf(folder.id, folder.name, folder.children.joinToString(ChildSeparator.toString()))
                .joinToString(Separator.toString())
        }

    private fun decodeFolders(raw: String): List<CompanionFolder> = raw
        .split(RecordSeparator)
        .filter { it.isNotBlank() }
        .mapNotNull { record ->
            val fields = record.split(Separator)
            if (fields.size < 3) return@mapNotNull null
            // An empty tome is a real thing the user made (CR-17) — it must survive a reboot, waiting to
            // be filled. Only a malformed record is dropped.
            val children = fields[2].split(ChildSeparator).filter { it.isNotBlank() }
            CompanionFolder(fields[0], fields[1], children)
        }

    private fun decodeChildCursors(raw: String): Map<String, String> = raw
        .split(RecordSeparator)
        .mapNotNull { record ->
            val fields = record.split(Separator)
            if (fields.size != 2 || fields.any(String::isBlank)) null else fields[0] to fields[1]
        }
        .toMap()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
}
