package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.home.HomeShelfRepository
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore

/**
 * App-owned production bindings: Library and Settings share the Room-backed LibraryStore.
 * FakeLibraryRepository is reserved for standalone previews and tests.
 */
object LibraryInjection {
    fun repository(context: Context): LibraryRepository = LibraryStore.repository(context)

    fun homeShelfRepository(context: Context): HomeShelfRepository =
        LibraryStore.homeShelfRepository(context)
}
