package com.example.synergic_pos_offline.fragments

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.DatabaseHelper
import com.example.synergic_pos_offline.database.GeneralSettingsDao
import com.example.synergic_pos_offline.utils.AutoBackup
import com.example.synergic_pos_offline.utils.DatabaseBackup
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.Downloads
import com.example.synergic_pos_offline.utils.SessionManager
import com.example.synergic_pos_offline.utils.SettingsCache
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * About App - what this installation is, what it is running on, and whether the two
 * are compatible.
 *
 * Everything here is read at the moment the screen opens rather than written down
 * anywhere: the version comes from the package manager, the compatibility from the
 * manifest's own minimum against the device's actual Android, the database figures
 * from the database. A screen that recited constants would go stale the first time
 * one of them changed and nobody would notice.
 *
 * The data backup lives at the foot of this screen. It belongs to the installation -
 * it is what an operator does before uninstalling and after reinstalling - rather
 * than to how the shop is configured, which is what the settings screens are for.
 */
class AboutAppFragment : Fragment(), TitledScreen {

    override val screenTitle = "About App"

    /**
     * Picks the backup file to restore from.
     *
     * Registered here rather than at the tap: a launcher has to exist before the
     * fragment is started, and registering one from a click listener throws.
     */
    private val pickBackup: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmRestore(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_about_app, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        showIdentity(view)
        showSections(view)

        bindAutoBackup(view)
        view.findViewById<MaterialButton>(R.id.btnBackupData).setOnClickListener { onBackup() }
        view.findViewById<MaterialButton>(R.id.btnRestoreData).setOnClickListener {
            // Anything, rather than a MIME filter: a .sql file is typed differently
            // by different file managers - text/plain here, application/octet-stream
            // there - and a filter that hides the operator's own backup is worse than
            // one that shows too much.
            pickBackup.launch(arrayOf("*/*"))
        }

        ThemeManager.applyTheme(view)
        // ThemeManager fills every MaterialButton; Restore is the secondary action
        // and keeps its outlined look.
        val accent = ThemeManager.getThemeColor(requireContext())
        view.findViewById<MaterialButton>(R.id.btnRestoreData).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(accent)
            strokeColor = android.content.res.ColorStateList.valueOf(accent)
        }
    }

    // ---- The head of the screen ----------------------------------------------

    private fun showIdentity(view: View) {
        val pkg = packageInfo()
        view.findViewById<TextView>(R.id.tvAboutName).text =
            requireContext().applicationInfo.loadLabel(requireContext().packageManager)
        view.findViewById<TextView>(R.id.tvAboutVersion).text =
            "Version ${pkg?.versionName ?: "?"} (build ${pkg?.let { versionCode(it) } ?: "?"})"

        // The verdict, stated rather than left to be worked out from two API numbers.
        val minSdk = minSdk()
        val supported = Build.VERSION.SDK_INT >= minSdk
        view.findViewById<TextView>(R.id.tvAboutCompatibility).apply {
            text = if (supported) {
                "✓ This device is supported\n" +
                    "Android ${Build.VERSION.RELEASE} is within the ${androidName(minSdk)} " +
                    "and above this app requires"
            } else {
                "✕ This device is below the minimum\n" +
                    "This app needs ${androidName(minSdk)} or newer; this is Android ${Build.VERSION.RELEASE}"
            }
            setTextColor(if (supported) Color.parseColor("#1B7F3B") else Color.parseColor("#B3261E"))
        }
    }

    // ---- The detail ----------------------------------------------------------

    private fun showSections(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.llAboutSections)
        container.removeAllViews()
        val pkg = packageInfo()

        container.addView(
            section(
                "APPLICATION",
                listOf(
                    "Package" to requireContext().packageName,
                    "Version name" to (pkg?.versionName ?: "unknown"),
                    "Version code" to (pkg?.let { versionCode(it).toString() } ?: "unknown"),
                    "Installed" to (pkg?.firstInstallTime?.let { stamp(it) } ?: "unknown"),
                    "Last updated" to (pkg?.lastUpdateTime?.let { stamp(it) } ?: "unknown"),
                    "App size" to fileSize(File(requireContext().applicationInfo.sourceDir).length())
                )
            )
        )

        container.addView(
            section(
                "COMPATIBILITY",
                listOf(
                    "Minimum Android" to "${androidName(minSdk())} (API ${minSdk()})",
                    "Built for" to "${androidName(targetSdk())} (API ${targetSdk()})",
                    "This device runs" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "Screen" to screenDescription(),
                    "Processor" to Build.SUPPORTED_ABIS.joinToString(", ")
                )
            )
        )

        container.addView(
            section(
                "DEVICE",
                listOf(
                    "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "Device" to Build.DEVICE,
                    "Build" to Build.DISPLAY
                )
            )
        )

        container.addView(section("DATA", dataRows()))
        container.addView(section("THIS INSTALLATION", installationRows()))
    }

    /** What the database holds, and how big it has grown. */
    private fun dataRows(): List<Pair<String, String>> = runCatching {
        val db = DatabaseHelper.getInstance(requireContext()).readableDatabase
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", null
        ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }

        var records = 0L
        tables.forEach { t ->
            runCatching {
                db.rawQuery("SELECT count(*) FROM $t", null).use { c ->
                    if (c.moveToFirst()) records += c.getLong(0)
                }
            }
        }
        listOf(
            "Database version" to db.version.toString(),
            "Tables" to tables.size.toString(),
            "Records" to records.toString(),
            "Database size" to fileSize(File(db.path).length())
        )
    }.getOrDefault(listOf("Database" to "could not be read"))

    /** Who this till belongs to and how it is set up. */
    private fun installationRows(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            DatabaseHelper.getInstance(requireContext()).readableDatabase.query(
                DatabaseHelper.Tables.MD_REGISTRATION,
                arrayOf("store_name", "store_id", "outlet_id", "store_gstin", "device_id"),
                null, null, null, null, "store_id ASC", "1"
            ).use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { rows.add("Store" to it) }
                    rows.add("Store ID" to (if (c.isNull(1)) "-" else c.getString(1)))
                    if (!c.isNull(2)) rows.add("Outlet ID" to c.getString(2))
                    c.getString(3)?.takeIf { it.isNotBlank() }?.let { rows.add("GSTIN" to it) }
                    c.getString(4)?.takeIf { it.isNotBlank() }?.let { rows.add("Device ID" to it) }
                }
            }
        }
        val mode = when (SettingsCache.value(requireContext(), "G", "Mode")) {
            "R" -> "Restaurant"
            "G" -> "Grocery"
            else -> "not set"
        }
        rows.add("Mode" to mode)
        rows.add(
            "Stock tracking" to
                if (GeneralSettingsDao.isStockEnabled(requireContext())) "On" else "Off"
        )
        rows.add("Signed in as" to (SessionManager.currentUser?.userId ?: "nobody"))
        return rows
    }

    // ---- Row and section builders --------------------------------------------

    /** A titled card of "label ........ value" rows. */
    private fun section(title: String, rows: List<Pair<String, String>>): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            rows.forEach { (label, value) -> addView(infoRow(label, value)) }
        }
        card.addView(body)
        return card
    }

    /**
     * One detail line.
     *
     * The value takes the right-hand side and is allowed to wrap: a GSTIN, a device
     * id or a build string is longer than the space beside its label, and truncating
     * the one thing someone opened this screen to read would defeat the point.
     */
    private fun infoRow(label: String, value: String): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 0.9f)
                text = label
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, null))
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1.1f)
                text = value
                textSize = 13f
                gravity = Gravity.END
                setTypeface(Typeface.MONOSPACE)
                setTextColor(resources.getColor(R.color.text_main, null))
            })
        }
    // ---- Automatic backup -----------------------------------------------------

    /**
     * The toggle, the gap between backups, and the button that stores them.
     *
     * Nothing here writes as it is touched. Changing a switch and a number with no
     * way to confirm them leaves an operator unable to tell whether the till took
     * the change - so the two are held until Save is pressed, and the line under the
     * switch reports what is *stored* rather than what is on screen. A pending
     * change and a saved one therefore look different.
     */
    private fun bindAutoBackup(view: View) {
        val toggle = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.swAutoBackup
        )
        val intervalRow = view.findViewById<View>(R.id.llAutoBackupInterval)
        val hours = view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etAutoBackupHours
        )
        val hoursField = view.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.tilAutoBackupHours
        )

        val current = AutoBackup.settings(requireContext())
        toggle.isChecked = current.enabled
        hours.setText(current.intervalHours.toString())
        intervalRow.visibility = if (current.enabled) View.VISIBLE else View.GONE
        showAutoBackupState(view)

        // The switch only opens and closes the row it governs; it is stored on Save
        // with everything else.
        toggle.setOnCheckedChangeListener { _, isChecked ->
            intervalRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            hoursField.error = null
        }
        // Clears a complaint as soon as the operator starts correcting it.
        hours.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { hoursField.error = null }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        view.findViewById<MaterialButton>(R.id.btnSaveAutoBackup).setOnClickListener {
            val typed = hours.text?.toString()?.trim().orEmpty()
            val value = AutoBackup.validHours(typed)
            if (toggle.isChecked && value == null) {
                // Said on the field itself, where the wrong value is.
                hoursField.error = hoursProblem(typed)
                return@setOnClickListener
            }
            hoursField.error = null

            val interval = value ?: AutoBackup.settings(requireContext()).intervalHours
            AutoBackup.save(requireContext(), toggle.isChecked, interval)
            hours.setText(interval.toString())
            showAutoBackupState(view)
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Saved",
                message = if (!toggle.isChecked) {
                    "Automatic backup is off. Backups are only taken when you press Backup."
                } else {
                    "A backup will be taken every " +
                        (if (interval == 1) "hour" else "$interval hours") +
                        ", while the app is open, into Downloads/POSbackup."
                }
            )
        }
    }

    /** Why [typed] was refused, in the words the operator needs to fix it. */
    private fun hoursProblem(typed: String): String = when {
        typed.isEmpty() -> "Enter how many hours"
        typed.toIntOrNull() == 0 -> "Must be at least ${AutoBackup.MIN_INTERVAL_HOURS} hour"
        (typed.toIntOrNull() ?: 0) > AutoBackup.MAX_INTERVAL_HOURS ->
            "At most ${AutoBackup.MAX_INTERVAL_HOURS} hours (a week)"
        else -> "Whole hours only"
    }

    private fun showAutoBackupState(view: View) {
        val settings = AutoBackup.settings(requireContext())
        view.findViewById<TextView>(R.id.tvAutoBackupState).text = if (!settings.enabled) {
            "Off - backups are only taken when you press Backup"
        } else {
            val every = if (settings.intervalHours == 1) "every hour"
            else "every ${settings.intervalHours} hours"
            "On - $every, while the app is open. " +
                "Last: ${AutoBackup.lastRunDescription(requireContext())}"
        }
    }

    // ---- Backup and restore ---------------------------------------------------

    /**
     * Writes the whole database out to Downloads, for the operator to keep.
     *
     * Off the main thread and streamed straight into the file. A shop with a year
     * of bills behind it has hundreds of thousands of rows: reading them on the main
     * thread freezes the till for as long as it takes and Android offers to close
     * it, which an operator reports as a crash - and assembling them into a single
     * String first runs it out of memory before a byte reaches the file.
     */
    private fun onBackup() = inBackground("Backing up…") {
        var summary: DatabaseBackup.Export? = null
        // The same folder and naming the automatic ones use: one place to look, and
        // one convention, whichever took the file.
        val now = Date()
        val savedTo = Downloads.stream(
            requireContext(), AutoBackup.fileName(now), "application/sql", AutoBackup.folderFor(now)
        ) { writer -> summary = DatabaseBackup.exportTo(requireContext(), writer) }
        val export = summary ?: error("nothing was written")

        // Says what was read as well as what was written. Reporting only the tables
        // that had records reads as though the rest were skipped, when every table
        // in the database was gone through.
        val emptyNote = if (export.empty.isEmpty()) ""
        else "\n\nThe other ${export.empty.size} table(s) were read and held no records."
        onMain {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Backup saved",
                message = "Read all ${export.scanned} table(s) in the database and wrote " +
                    "${export.rows} record(s) from the ${export.tables} that hold data." +
                    emptyNote +
                    "\n\nSaved to $savedTo. Keep this file somewhere off the device " +
                    "before uninstalling."
            )
        }
    }

    /**
     * Says what a restore is about to do before it does it.
     *
     * It replaces the books wholesale and cannot be undone from the till, so the
     * file is checked first - an operator who picked the wrong file finds out here
     * rather than after their data has gone.
     */
    private fun confirmRestore(uri: Uri) {
        // Only the head is read here: enough to tell a backup from whatever else the
        // operator might have picked, without pulling the whole file into memory to
        // ask one question. The restore itself streams it.
        val head = try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().useLines { lines -> lines.take(200).joinToString("\n") }
            }.orEmpty()
        } catch (e: Exception) {
            toast("Could not read that file: ${e.message}")
            return
        }
        if (!head.contains("INSERT INTO ", ignoreCase = true) &&
            !head.contains("Synergic POS data backup")
        ) {
            toast("That file is not a Synergic POS backup")
            return
        }

        // A backup from a different schema still restores - the tables it does not
        // know about are left alone - but it is worth saying so first.
        val taken = DatabaseBackup.schemaVersionOf(head)
        val here = DatabaseHelper.getInstance(requireContext()).readableDatabase.version
        val mismatch = if (taken != null && taken != here) {
            "\n\nThis backup was taken from database version $taken and this app is on " +
                "$here. Anything it does not recognise will be left as it is."
        } else ""

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Restore data?",
            message = "This replaces everything on this device with the backup - products, " +
                "customers, bills, settings, the store registration and the users. It " +
                "cannot be undone." +
                "\n\nYou will be signed out afterwards. Sign back in with the login from " +
                "the device the backup was taken on: this device's own users are replaced " +
                "too.$mismatch",
            positiveText = "Restore",
            negativeText = "Cancel",
            destructive = true
        ) { runRestore(uri, taken) }
    }

    /** Streams the file into the database, off the main thread. */
    private fun runRestore(uri: Uri, schemaVersion: Int?) = inBackground("Restoring…") {
        val result = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().useLines { lines ->
                DatabaseBackup.restore(requireContext(), lines, schemaVersion)
            }
        } ?: DatabaseBackup.Result(0, 0, 0, schemaVersion, "that file could not be opened")

        onMain {
            if (!result.ok) {
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Restore failed",
                    message = "${result.error}\n\nNothing was changed."
                )
                return@onMain
            }
            val skipped = if (result.skipped > 0) {
                "\n\n${result.skipped} record(s) were for tables this version does not " +
                    "have, and were skipped."
            } else ""
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Restored",
                message = "${result.rows} record(s) into ${result.tables} table(s).$skipped" +
                    "\n\nSigning you out. Sign back in with the login from the device the " +
                    "backup came from.",
                onDismiss = { signOutIntoRestoredData() }
            )
        }
    }

    /**
     * Signs out and returns to the login screen, so the app reads itself back from
     * the data that was just restored.
     *
     * This is the step that makes a restore visible. The signed-in session is a copy
     * taken at login - the operator, and the store they belong to - and thirty-odd
     * queries filter their rows by that store. Restoring the database underneath a
     * live session leaves every one of them asking for the store this device used to
     * be, so the restored records are there and nothing shows them. Signing out
     * throws that copy away; the next login rebuilds it from the restored users and
     * registration.
     */
    private fun signOutIntoRestoredData() {
        SessionManager.logout()
        val fm = requireActivity().supportFragmentManager
        fm.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction().replace(R.id.fragment_container, LoginFragment()).commit()
    }

    /**
     * Runs [work] on a worker thread behind a dialog that cannot be dismissed.
     *
     * Both of these read or write the whole database, which is not main-thread work
     * on a real shop's data. The dialog blocks the screen because there is nothing
     * useful to do on it meanwhile - though the restore is one transaction, so
     * leaving would not have corrupted anything either.
     *
     * Anything [work] throws is caught and shown rather than allowed to reach the
     * operator as a closed app.
     */
    private fun inBackground(message: String, work: () -> Unit) {
        val progress = progressDialog(message)
        Thread {
            var failure: Exception? = null
            try {
                work()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Background work failed", e)
                failure = e
            }
            onMain {
                runCatching { progress.dismiss() }
                failure?.let {
                    DialogUtils.showSuccess(
                        context = requireContext(),
                        title = "That did not finish",
                        message = it.message ?: it.javaClass.simpleName
                    )
                }
            }
        }.start()
    }

    /** A spinner and a line of text, shown while the database is being read or written. */
    private fun progressDialog(message: String): androidx.appcompat.app.AlertDialog {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(android.widget.ProgressBar(context))
            addView(TextView(context).apply {
                text = message
                textSize = 15f
                setPadding(dp(16), 0, 0, 0)
                setTextColor(resources.getColor(R.color.text_main, null))
            })
        }
        return androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(row)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    /** Runs [block] on the main thread, and only while the screen is still there. */
    private fun onMain(block: () -> Unit) {
        activity?.runOnUiThread { if (isAdded) block() }
    }

    // ---- Small helpers --------------------------------------------------------

    private fun packageInfo(): PackageInfo? = runCatching {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versionCode(pkg: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
        else pkg.versionCode.toLong()

    private fun minSdk(): Int = requireContext().applicationInfo.minSdkVersion

    private fun targetSdk(): Int = requireContext().applicationInfo.targetSdkVersion

    /**
     * The marketing name for an API level - "Android 7.0", not "API 24".
     *
     * Only the levels this app can actually meet are named; anything past the table
     * is reported by its number, which is better than naming it wrongly.
     */
    private fun androidName(sdk: Int): String = when (sdk) {
        24 -> "Android 7.0"
        25 -> "Android 7.1"
        26 -> "Android 8.0"
        27 -> "Android 8.1"
        28 -> "Android 9"
        29 -> "Android 10"
        30 -> "Android 11"
        31, 32 -> "Android 12"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        36 -> "Android 16"
        else -> "API $sdk"
    }

    private fun screenDescription(): String {
        val m = resources.displayMetrics
        val widthDp = (m.widthPixels / m.density).toInt()
        val heightDp = (m.heightPixels / m.density).toInt()
        return "${m.widthPixels} x ${m.heightPixels} (${widthDp} x ${heightDp} dp, ${m.densityDpi} dpi)"
    }

    private fun fileSize(bytes: Long): String = when {
        bytes <= 0 -> "unknown"
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date(millis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "AboutApp"
    }
}
