package com.example.synergic_pos_offline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.synergic_pos_offline.utils.DatabaseBackup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A one-off recovery helper: restores the emulator's own data from a backup file.
 *
 * Not a test of anything - it is the Restore Data button, driven from here because
 * tapping through a system file picker with `adb input` is far less reliable than
 * calling the code that button calls. Delete it once the emulator is back.
 */
@RunWith(AndroidJUnit4::class)
class RestoreEmulatorDataTest {

    @Test
    fun restoreFromDownloadsBackup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.filesDir, "restore.sql")
        assertTrue("backup file not readable at ${file.path}", file.canRead())

        val result = file.bufferedReader().useLines { DatabaseBackup.restore(ctx, it) }
        println("RESTORE: ok=${result.ok} tables=${result.tables} rows=${result.rows} " +
            "skipped=${result.skipped} error=${result.error}")
        assertTrue("restore failed: ${result.error}", result.ok)
    }
}
