package com.example.synergic_pos_offline.utils

import android.content.ContentValues
import android.content.Context
import android.provider.Settings
import com.example.synergic_pos_offline.database.DatabaseHelper

/**
 * Which device this installation is, and what to do when a restored backup says it
 * is a different one.
 *
 * ## The problem this solves
 *
 * A backup carries the store registration, and the registration carries the
 * `device_id` of the device the backup was taken on. Restore it onto a replacement
 * tablet and the till is now describing hardware that is sitting in a drawer: the
 * store is right, the users are right, and the one field that says *where* this
 * installation is running is wrong. Every later conversation with the server - which
 * identifies a till by its device id - would be about the old device.
 *
 * [adopt] fixes that by writing this device's id over the restored one. It is the
 * last step of a restore, and it is why a shop can move to a new tablet by restoring
 * a file rather than registering again.
 *
 * ## What it deliberately does not do
 *
 * It does not touch `verify_flag`. A restore is not an authorisation: if the backup
 * came from a verified store the login works, and if it did not, adopting the device
 * id must not be a way to make an unverified till behave like a verified one.
 *
 * It does not rewrite the `device_id` mirrored onto each `md_app_settings` row
 * either. Those record which device wrote a setting, and that is history - it was a
 * different device, and saying otherwise would be a lie in an audit column.
 */
object DeviceIdentity {

    /**
     * This device's id: `ANDROID_ID`, which is what registration and the login
     * screen's verification check already send to the server, so all three agree on
     * what "this device" means.
     */
    fun current(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /**
     * What [adopt] found and did.
     *
     * [changed] is false both when the backup already named this device - restoring
     * onto the same tablet - and when there was no registration to correct.
     */
    data class Adoption(
        val previous: String?,
        val current: String,
        val changed: Boolean,
        val storeId: Long?
    )

    /**
     * Makes the restored registration describe *this* device.
     *
     * Every registration row is updated rather than only the first: a till holds one
     * store, and leaving a second row pointing at the old hardware would mean the
     * answer depended on which row was read first.
     */
    fun adopt(context: Context): Adoption {
        val db = DatabaseHelper.getInstance(context).writableDatabase
        val now = current(context)

        var previous: String? = null
        var storeId: Long? = null
        db.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id", "store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c ->
            if (c.moveToFirst()) {
                previous = if (c.isNull(0)) null else c.getString(0)
                storeId = if (c.isNull(1)) null else c.getLong(1)
            }
        }

        // Nothing registered, or no id of our own to write.
        if (storeId == null) return Adoption(previous, now, changed = false, storeId = null)
        if (now.isBlank()) return Adoption(previous, now, changed = false, storeId = storeId)

        // Asked of every row rather than of the first one read above. A device that
        // has been through a restore can hold more than one registration, and
        // deciding on the first would leave the rest naming the old tablet for good -
        // the answer would then depend on which row happened to be read first.
        val stale = db.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.Tables.MD_REGISTRATION} " +
                "WHERE device_id IS NULL OR device_id <> ?",
            arrayOf(now)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (stale == 0) return Adoption(previous, now, changed = false, storeId = storeId)

        db.update(
            DatabaseHelper.Tables.MD_REGISTRATION,
            ContentValues().apply { put("device_id", now) },
            null, null
        )
        return Adoption(previous, now, changed = true, storeId = storeId)
    }

    /** What came of telling the server, for the restore to report. */
    data class Published(
        /** False when there was nothing to publish, or nobody to publish it for. */
        val attempted: Boolean,
        val ok: Boolean,
        val userId: Long? = null,
        val error: String? = null
    )

    /**
     * Tells the server that this store now runs on this device.
     *
     * `POST /admin/edit_device { id, device_id }`, where `id` is the row id of the
     * store's own user in `md_users` - not the store id, and not the login name. A
     * restore brings that id across unchanged with the rest of the table, which is
     * what makes it usable as the server's handle on this till: the row the server
     * knows and the row this device now holds are the same row.
     *
     * Best-effort, and deliberately so. [adopt] has already put the local database
     * right, and the till can sell, print and be logged into with no network at all;
     * this is the server catching up. A shop restoring onto a replacement tablet in a
     * back room with no signal must not be stopped by it - so a failure is reported,
     * not raised, and the restore stands.
     *
     * Blocking: call it from a background thread.
     */
    fun publish(context: Context, adoption: Adoption): Published {
        // Nothing moved - restoring onto the same tablet tells the server nothing new.
        if (!adoption.changed) return Published(attempted = false, ok = true)
        return send(context, adoption.current, adoption.storeId)
    }

    /**
     * Sends a move that an earlier attempt could not.
     *
     * Returns null when there is nothing outstanding - which is the normal case, so
     * callers can run it on every login screen without thinking about it.
     *
     * A restore usually happens on a tablet being set up before it has been given the
     * shop's wifi, so the first attempt failing is ordinary rather than exceptional.
     * Left at that, the office would go on believing the store was running on a
     * device in a drawer until somebody noticed. Retrying costs one request on a
     * screen that already makes one.
     */
    fun publishPending(context: Context): Published? {
        val pending = prefs(context).getString(KEY_PENDING, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val now = current(context)
        // The device this was queued for is no longer the device running the app -
        // the backup has been moved on again, and what is queued is stale.
        if (pending != now) {
            clearPending(context)
            return null
        }
        return send(context, now, registeredStoreId(context))
    }

    /** The one place the request is actually made, by either route. */
    private fun send(context: Context, deviceId: String, storeId: Long?): Published {
        val userId = userIdFor(context, storeId)
            ?: return Published(
                attempted = false, ok = false,
                error = "no user was found to identify this till by"
            )

        val payload = org.json.JSONObject()
            .put("id", userId)
            .put("device_id", deviceId)
        val result = ApiClient.postJson(ApiClient.PATH_EDIT_DEVICE, payload)

        if (result.ok) {
            clearPending(context)
            return Published(attempted = true, ok = true, userId = userId)
        }

        android.util.Log.w(
            TAG, "edit_device failed (${result.status}): ${result.error ?: result.body}"
        )
        // Queued rather than lost, so the next screen with a connection finishes it.
        prefs(context).edit().putString(KEY_PENDING, deviceId).apply()
        return Published(
            attempted = true, ok = false, userId = userId, error = reasonFor(result)
        )
    }

    /**
     * Why the send failed, in words an operator can act on.
     *
     * [ApiClient] reports a request that never left the device as status -1 carrying
     * the exception's own text - "Unable to resolve host …: No address associated
     * with hostname" for a tablet with no connection, which is the usual case here
     * and says nothing to the person reading it. Anything the server itself answered
     * is quoted by its status instead, because that is a problem at the other end and
     * the number is what identifies it.
     */
    fun reasonFor(result: ApiClient.ApiResult): String = when {
        result.status == -1 -> "this tablet is not connected to the internet"
        result.status in 500..599 -> "the server had a problem (${result.status})"
        else -> "the server refused it (${result.status})"
    }

    /** Whether a move is still waiting to be sent. */
    fun hasPending(context: Context): Boolean =
        !prefs(context).getString(KEY_PENDING, null).isNullOrBlank()

    private fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    /**
     * Held in preferences rather than in the database on purpose: it is a note about
     * this installation's conversation with the server, not something the shop owns.
     * Restoring a backup, or putting the settings back to their defaults, must not
     * make an unsent move disappear.
     */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun registeredStoreId(context: Context): Long? {
        DatabaseHelper.getInstance(context).readableDatabase.query(
            DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("store_id"),
            null, null, null, null, "store_id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    /**
     * The row id of the user the server knows this till by.
     *
     * The store's own admin ('A') is the row registration creates and the server
     * therefore holds; 'S' is a support login and 'G' an ordinary cashier, so the
     * ordering below prefers the admin and only falls back when a restored database
     * has none. Scoped to the registered store where there is one, because a device
     * that has been through a restore can hold users from more than one.
     */
    fun userIdFor(context: Context, storeId: Long?): Long? {
        val db = DatabaseHelper.getInstance(context).readableDatabase
        val where = if (storeId != null) "store_id = ?" else null
        val args = if (storeId != null) arrayOf(storeId.toString()) else null
        db.query(
            DatabaseHelper.Tables.MD_USERS, arrayOf("id"),
            where, args, null, null,
            "CASE role WHEN 'A' THEN 0 WHEN 'S' THEN 1 ELSE 2 END ASC, id ASC", "1"
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private const val TAG = "DeviceIdentity"
    private const val PREF = "device_identity"

    /** The device id a move is still waiting to tell the server about. */
    private const val KEY_PENDING = "pending_device_id"
}
