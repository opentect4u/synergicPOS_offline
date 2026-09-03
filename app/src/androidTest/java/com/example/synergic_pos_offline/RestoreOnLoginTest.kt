package com.example.synergic_pos_offline

import android.content.ContentValues
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.utils.ApiClient
import com.example.synergic_pos_offline.utils.BackupFiles
import com.example.synergic_pos_offline.utils.DeviceIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restoring a backup onto a device that has nothing on it - the route out of a lost
 * or replaced tablet.
 *
 * The device id is what this pins down. A backup names the tablet it was taken on,
 * and restoring it onto a replacement leaves the store's registration describing
 * hardware in a drawer; adopting this device is what lets the till be logged into at
 * all, so it is the part that has to be right.
 */
@RunWith(AndroidJUnit4::class)
class RestoreOnLoginTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = DatabaseHelper.getInstance(ctx).writableDatabase

    private val storeName = "Restore On Login Store"

    @After
    fun clearLeftovers() {
        db.delete(
            DatabaseHelper.Tables.MD_USERS, "user_id LIKE ?", arrayOf("RESTORE_TEST_%")
        )
        db.delete(DatabaseHelper.Tables.MD_REGISTRATION, "store_name = ?", arrayOf(storeName))
    }

    /** A user of [role] belonging to [storeId]; returns its row id. */
    private fun seedUser(storeId: Long, role: String, login: String): Long = db.insert(
        DatabaseHelper.Tables.MD_USERS, null,
        ContentValues().apply {
            put("store_id", storeId)
            put("user_id", login)
            put("user_name", login)
            put("role", role)
            put("is_blocked", 0)
        }
    )

    /** A registration that came out of a backup taken on some other tablet. */
    private fun seedForeignRegistration(deviceId: String): Long = db.insert(
        DatabaseHelper.Tables.MD_REGISTRATION, null,
        ContentValues().apply {
            put("store_name", storeName)
            put("device_id", deviceId)
            put("verify_flag", 1)
        }
    )

    private fun deviceIdOf(storeId: Long): String? = db.query(
        DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("device_id"),
        "store_id = ?", arrayOf(storeId.toString()), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun verifyFlagOf(storeId: Long): Int = db.query(
        DatabaseHelper.Tables.MD_REGISTRATION, arrayOf("verify_flag"),
        "store_id = ?", arrayOf(storeId.toString()), null, null, null, "1"
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    @Test
    fun theRestoredTillBecomesThisDevice() {
        val androidId =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        assertTrue("this test needs a device with an ANDROID_ID", androidId.isNotBlank())

        val storeId = seedForeignRegistration("A_TABLET_IN_A_DRAWER")
        assertTrue("could not seed a registration", storeId != -1L)

        val adoption = DeviceIdentity.adopt(ctx)

        assertEquals("the adopted id should be this device's", androidId, adoption.current)
        assertEquals(
            "the restored registration should now name this device",
            androidId, deviceIdOf(storeId)
        )
    }

    /**
     * Restoring onto the same tablet changes nothing - so an operator who restores a
     * backup to undo a mistake is not told their device was replaced.
     */
    @Test
    fun adoptingTwiceIsANoOp() {
        seedForeignRegistration("A_TABLET_IN_A_DRAWER")
        DeviceIdentity.adopt(ctx)

        val second = DeviceIdentity.adopt(ctx)

        assertFalse("nothing was left to correct", second.changed)
    }

    /**
     * Adopting a device is not a way to become verified.
     *
     * A restore moves the store's own verification across with the registration; it
     * must not be able to raise it. If it could, a backup edited by hand would be a
     * route into an unverified till.
     */
    @Test
    fun adoptingDoesNotTouchVerification() {
        val storeId = seedForeignRegistration("A_TABLET_IN_A_DRAWER")
        db.update(
            DatabaseHelper.Tables.MD_REGISTRATION,
            ContentValues().apply { put("verify_flag", 0) },
            "store_id = ?", arrayOf(storeId.toString())
        )

        DeviceIdentity.adopt(ctx)

        assertEquals("an unverified store stays unverified", 0, verifyFlagOf(storeId))
    }

    /**
     * The server is told about the move by the store's *admin* user's row id.
     *
     * `/admin/edit_device` takes `id` - the `md_users` row id, not the store id and
     * not the login name - and a restore brings that id across unchanged, which is
     * what makes it the handle the server and this device agree on. Sending a
     * cashier's id instead would move the wrong record.
     */
    @Test
    fun theServerIsToldByTheStoresAdminUserId() {
        val storeId = seedForeignRegistration("A_TABLET_IN_A_DRAWER")
        // Inserted cashier-first, so a lookup that simply took the lowest id would
        // pick the wrong one and fail here.
        seedUser(storeId, "G", "RESTORE_TEST_CASHIER")
        val admin = seedUser(storeId, "A", "RESTORE_TEST_ADMIN")

        assertEquals(
            "the admin's row id is what the server knows this till by",
            admin, DeviceIdentity.userIdFor(ctx, storeId)
        )
    }

    /** With no admin restored, the support login stands in before any cashier. */
    @Test
    fun aSupportLoginStandsInWhenThereIsNoAdmin() {
        val storeId = seedForeignRegistration("A_TABLET_IN_A_DRAWER")
        seedUser(storeId, "G", "RESTORE_TEST_CASHIER")
        val support = seedUser(storeId, "S", "RESTORE_TEST_SUPPORT")

        assertEquals(support, DeviceIdentity.userIdFor(ctx, storeId))
    }

    /**
     * Restoring onto the same tablet tells the server nothing - and, importantly,
     * does not reach for the network to say so.
     */
    @Test
    fun nothingIsPublishedWhenNothingMoved() {
        seedForeignRegistration("A_TABLET_IN_A_DRAWER")
        DeviceIdentity.adopt(ctx)

        val published = DeviceIdentity.publish(ctx, DeviceIdentity.adopt(ctx))

        assertFalse("there was nothing to tell the server", published.attempted)
        assertTrue("and that is not a failure", published.ok)
    }

    /** The queue of unsent device moves, as [DeviceIdentity] stores it. */
    private val pendingPrefs
        get() = ctx.getSharedPreferences("device_identity", android.content.Context.MODE_PRIVATE)

    /**
     * A tablet with no connection is told so, not shown the exception.
     *
     * This is what an operator meets when they restore onto a replacement device
     * before it has the shop's wifi - the ordinary case. Quoting Android's own
     * "Unable to resolve host …: No address associated with hostname" at them names
     * a hostname they have never heard of and suggests the app is broken.
     */
    @Test
    fun anUnreachableServerIsExplainedRatherThanQuoted() {
        val offline = ApiClient.ApiResult(
            ok = false, status = -1, body = "",
            error = "Unable to resolve host \"webbackend.synergicpos.in\": " +
                "No address associated with hostname"
        )

        val reason = DeviceIdentity.reasonFor(offline)

        assertEquals("this tablet is not connected to the internet", reason)
        assertFalse(
            "the raw exception should not reach the operator",
            reason.contains("resolve host") || reason.contains("hostname")
        )
        // Something the server itself answered is a different problem, and says so.
        assertTrue(
            DeviceIdentity.reasonFor(
                ApiClient.ApiResult(false, 500, "", null)
            ).contains("500")
        )
    }

    /** With nothing queued, the retry costs nothing and reaches for no network. */
    @Test
    fun nothingIsSentWhenNothingIsQueued() {
        pendingPrefs.edit().clear().commit()

        assertFalse(DeviceIdentity.hasPending(ctx))
        assertNull("there was nothing to send", DeviceIdentity.publishPending(ctx))
    }

    /**
     * A move queued for a device this no longer is gets dropped, not sent.
     *
     * The backup has been carried on to a third tablet; telling the server about the
     * second one would move the store onto hardware nobody is using.
     */
    @Test
    fun aQueuedMoveForADifferentDeviceIsDropped() {
        pendingPrefs.edit().putString("pending_device_id", "SOME_OTHER_TABLET").commit()
        assertTrue(DeviceIdentity.hasPending(ctx))

        assertNull(DeviceIdentity.publishPending(ctx))
        assertFalse("the stale move should be cleared", DeviceIdentity.hasPending(ctx))
    }

    /**
     * Looking for backups never throws, whatever the device lets the app see.
     *
     * An empty list is the normal answer on a fresh installation - Android does not
     * let it read what the previous installation wrote - and the login screen falls
     * back to the file picker. What it must not do is fail on the way there.
     */
    @Test
    fun lookingForBackupsIsAlwaysSafe() {
        val found = BackupFiles.list(ctx)
        assertNotNull(found)
        found.forEach {
            assertTrue("a listed backup should be a .sql file", it.name.endsWith(".sql", true))
        }
    }

    /** Backups are told apart from whatever else an operator might pick. */
    @Test
    fun onlyABackupLooksLikeABackup() {
        assertTrue(BackupFiles.looksLikeBackup("-- Synergic POS data backup\n-- taken: now"))
        assertTrue(BackupFiles.looksLikeBackup("INSERT INTO md_products (\"id\") VALUES (1);"))
        assertFalse(BackupFiles.looksLikeBackup("just some text, not a backup at all"))
        assertFalse(BackupFiles.looksLikeBackup(""))
    }

    /**
     * The login screen offers both ways in - in portrait *and* in landscape.
     *
     * Both are asserted because the screen has a `layout-land` variant of its own,
     * and a tablet - which is what this app runs on - is usually in it. A link added
     * to one file and not the other is invisible on exactly the devices the shop
     * uses, and nothing about inflating the default layout would have said so.
     */
    @Test
    fun theLoginScreenOffersBothWaysInEitherWayUp() {
        listOf(
            "portrait" to android.content.res.Configuration.ORIENTATION_PORTRAIT,
            "landscape" to android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ).forEach { (name, orientation) ->
            val config = android.content.res.Configuration(ctx.resources.configuration).apply {
                this.orientation = orientation
                // The qualifier is picked from the dp figures as well as the flag, so
                // both are set - otherwise a portrait device keeps its own layout
                // whatever the orientation field says.
                val long = maxOf(screenWidthDp, screenHeightDp)
                val short = minOf(screenWidthDp, screenHeightDp)
                screenWidthDp = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) long else short
                screenHeightDp = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) short else long
            }
            val themed = androidx.appcompat.view.ContextThemeWrapper(
                ctx.createConfigurationContext(config),
                com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
            )
            val view: View =
                LayoutInflater.from(themed).inflate(R.layout.fragment_login, null, false)

            listOf(
                R.id.tilUsername, R.id.tilPassword, R.id.etUsername, R.id.etPassword,
                R.id.btnLogin, R.id.swipeRefresh, R.id.tvPending,
                R.id.tvRegister, R.id.tvRestoreData
            ).forEach {
                assertNotNull(
                    "missing from the $name login layout: " +
                        ctx.resources.getResourceEntryName(it),
                    view.findViewById<View>(it)
                )
            }

            // Register is revealed by the server check; Restore Data is the recovery
            // route and must be there before any of that resolves.
            assertEquals(
                "Register should start hidden in $name",
                View.GONE, view.findViewById<View>(R.id.tvRegister).visibility
            )
            assertEquals(
                "Restore Data should always be offered in $name",
                View.VISIBLE, view.findViewById<View>(R.id.tvRestoreData).visibility
            )
        }
    }
}
