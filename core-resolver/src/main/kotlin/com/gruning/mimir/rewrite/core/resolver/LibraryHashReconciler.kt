package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao

/** DAO-backed orchestration seam for atomic, offline, byte-exact library reconciliation. */
class LibraryHashReconciler(
    private val dao: GameDao,
    private val identityDao: GameIdentityDao? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(): Int =
        identityDao?.reconcileExactHashDuplicates(nowMillis()) ?: dao.reconcileExactHashDuplicates()
}
