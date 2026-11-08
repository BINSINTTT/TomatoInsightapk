package com.tomatoinsight.data

import android.content.Context
import com.tomatoinsight.clearScanReminderCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps phone farm caches isolated per Firebase Auth uid.
 *
 * Room rows are already keyed by userId. Shared prefs (scan reminders) and
 * in-memory sync can still leak across accounts on signup/login, so this
 * tags the active owner and blocks uploading another account's leftover farm.
 */
object FarmAccountIsolation {
    private const val PREFS = "tomatoinsight_farm_session"
    private const val KEY_OWNER_UID = "tomatoInsight.dataOwnerUid"

    fun ownerUid(context: Context): String? {
        val value = prefs(context).getString(KEY_OWNER_UID, null)?.trim().orEmpty()
        return value.ifBlank { null }
    }

    fun claimOwner(context: Context, uid: String) {
        if (uid.isBlank()) return
        prefs(context).edit().putString(KEY_OWNER_UID, uid).apply()
    }

    fun clearOwner(context: Context) {
        prefs(context).edit().remove(KEY_OWNER_UID).apply()
    }

    fun ownsFarmCache(context: Context, uid: String): Boolean {
        if (uid.isBlank()) return false
        return ownerUid(context) == uid
    }

    /**
     * Drop shared (non-Room) caches that still belong to a different account.
     * Room data stays keyed by uid so other accounts can log back in later.
     */
    fun discardForeignSharedCache(context: Context, uid: String): Boolean {
        if (uid.isBlank()) return false
        val owner = ownerUid(context) ?: return false
        if (owner == uid) return false
        clearScanReminderCache(context)
        clearOwner(context)
        return true
    }

    /**
     * After a successful signup: wipe shared reminder cache and ensure this
     * brand-new uid starts with an empty local farm before profile/farm saves.
     */
    suspend fun prepareFreshSignup(context: Context, uid: String, farmRepository: UserFarmRepository) {
        if (uid.isBlank()) return
        clearScanReminderCache(context)
        clearOwner(context)
        withContext(Dispatchers.IO) {
            farmRepository.clearForUser(uid)
        }
        claimOwner(context, uid)
    }

    fun onSignedOut(context: Context) {
        clearScanReminderCache(context)
        clearOwner(context)
    }

    /** True when sync may upload local farm rows for this uid (owner must be claimed). */
    fun mayUploadLocalFarm(context: Context, uid: String): Boolean = ownsFarmCache(context, uid)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
