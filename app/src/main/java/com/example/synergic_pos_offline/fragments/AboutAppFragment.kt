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
import com.example.synergic_pos_offline.database.UserDao
import com.example.synergic_pos_offline.utils.AutoBackup
import com.example.synergic_pos_offline.utils.BackupFiles
import com.example.synergic_pos_offline.utils.BillErase
import com.example.synergic_pos_offline.utils.BusyDialog
import com.example.synergic_pos_offline.utils.DatabaseBackup
import com.example.synergic_pos_offline.utils.DefaultSettings
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.Downloads
import com.example.synergic_pos_offline.utils.MasterData
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

    /** Picks the master-table export to load, when browsing rather than choosing
     *  from the ones this app can still see. */
    private val pickMasters: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmRestoreMasters(it) }
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

        view.findViewById<MaterialButton>(R.id.btnExportMasters).setOnClickListener {
            onExportMasters()
        }
        view.findViewById<MaterialButton>(R.id.btnRestoreMasters).setOnClickListener {
            chooseMasterExport()
        }

        view.findViewById<MaterialButton>(R.id.btnEraseBills).setOnClickListener {
            confirmEraseBills()
        }
        view.findViewById<MaterialButton>(R.id.btnRestoreDefaults).setOnClickListener {
            confirmRestoreDefaults()
        }

        styleButtons(view)
    }

    /**
     * The outlined buttons ThemeManager has just filled in.
     *
     * It colours every MaterialButton with the accent, which is right for the two
     * primary actions and wrong for these: Restore data is the secondary half of a
     * pair and keeps its outline, and the two that cannot be undone are drawn in the
     * same red as the warnings they open - a button that looks like every other
     * button on the screen is one an operator presses to find out what it does.
     */
    private fun styleButtons(view: View) {
        ThemeManager.applyTheme(view)
        val accent = ThemeManager.getThemeColor(requireContext())
        // Every button here is set explicitly. ThemeManager decides filled or
        // outlined from the view id's *name* - anything containing "back" is treated
        // as a secondary action - and "btnBackupData" and "btnSaveAutoBackup" both
        // match that by accident, which left them outlined when they are the primary
        // action of their row.
        fill(view.findViewById(R.id.btnSaveAutoBackup), accent)
        fill(view.findViewById(R.id.btnBackupData), accent)
        outline(view.findViewById(R.id.btnRestoreData), accent)
        fill(view.findViewById(R.id.btnExportMasters), accent)
        outline(view.findViewById(R.id.btnRestoreMasters), accent)
        val destructive = Color.parseColor(DialogUtils.DESTRUCTIVE_COLOR)
        outline(view.findViewById(R.id.btnEraseBills), destructive)
        outline(view.findViewById(R.id.btnRestoreDefaults), destructive)
    }

    /** The primary half of a pair: solid accent, white lettering. */
    private fun fill(button: MaterialButton, color: Int) = button.apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        setTextColor(Color.WHITE)
        strokeWidth = 0
    }

    /** The secondary half: the accent as an outline, on the card's own white. */
    private fun outline(button: MaterialButton, color: Int) = button.apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        setTextColor(color)
        strokeColor = android.content.res.ColorStateList.valueOf(color)
        strokeWidth = (resources.displayMetrics.density * 1.5f).toInt()
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

    // ---- The two gates in front of both of the buttons below -------------------

    /**
     * Asks for the signed-in operator's password, and runs [work] only if it is
     * right.
     *
     * The second half of a two-step confirmation: the warning before it says what is
     * about to happen and stops the accident, this says who is asking and stops the
     * stranger. Both irreversible actions go through it, so neither is one tap away
     * from a till left open on this screen while its operator is serving somebody.
     *
     * Checked against the stored hash rather than the password held in the session -
     * it is the same check the login screen makes, so a password changed since login
     * is the one that works here too. Anyone signed in necessarily has one: the login
     * that got them here verified it.
     */
    private fun withPassword(action: String, confirmText: String, work: () -> Unit) {
        val userId = SessionManager.currentUser?.userId
        if (userId.isNullOrBlank()) {
            // Not reachable through the app - this screen is behind the login - but a
            // destructive action must fail closed rather than assume.
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Sign in first",
                message = "$action needs the password of the operator using this till, " +
                    "and nobody is signed in."
            )
            return
        }
        val users = UserDao(requireContext())
        DialogUtils.showPasswordConfirm(
            context = requireContext(),
            title = "Confirm it's you",
            message = "Enter the password for $userId to $action.",
            positiveText = confirmText,
            destructive = true,
            verify = { typed -> users.verifyPassword(userId, typed) },
            onConfirmed = work
        )
    }

    // ---- The safety net under both of the buttons below ------------------------

    /**
     * Takes the backup that goes before anything irreversible, or explains why it
     * could not and stops the caller.
     *
     * Returns where the file went, or null when the operator should be left exactly
     * as they were. Nothing on this screen destroys anything without a copy of the
     * till on disk first: the backup is not a courtesy, it is the only way back, and
     * an action that went ahead after its safety net failed to deploy would be
     * unrecoverable in the one case where it mattered.
     *
     * Called from a worker thread - it reads the whole database.
     */
    private fun safetyBackup(action: String): String? = try {
        AutoBackup.backupBefore(requireContext(), action)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Safety backup before $action failed", e)
        onMain {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Nothing was changed",
                message = "A full backup is taken before anything is erased, and this one " +
                    "could not be written: ${e.message ?: e.javaClass.simpleName}." +
                    "\n\nSo $action was not carried out. Check there is room on the " +
                    "device and try again."
            )
        }
        null
    }

    // ---- Erase bills ----------------------------------------------------------

    /**
     * Says what erasing the bills takes and, just as importantly, what it leaves.
     *
     * The kept list is the half an operator does not expect. Erasing the bills does
     * not settle what a customer owes on a credit sale and does not put sold stock
     * back on the shelf, so both are named rather than discovered a week later. The
     * counter is the other one: sale returns and credit recoveries are numbered from
     * the same run as bills, so numbering only truly starts again when there are
     * none of those either - and when there are, the warning says so instead of
     * promising a fresh start it cannot give.
     */
    private fun confirmEraseBills() {
        val preview = BillErase.preview(requireContext())
        if (preview.bills == 0) {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "No bills to erase",
                message = "There are no bills on this device."
            )
            return
        }

        val counter = if (!preview.sharesCounter) {
            "\n\n• Numbering starts again from the Start No. in Bill Settings."
        } else {
            val kept = listOfNotNull(
                preview.saleReturns.takeIf { it > 0 }
                    ?.let { "$it sale return${if (it == 1) "" else "s"}" },
                preview.creditRecoveries.takeIf { it > 0 }
                    ?.let { "$it credit recover${if (it == 1) "y" else "ies"}" }
            ).joinToString(" and ")
            "\n\n• $kept are kept, and they are numbered from the same run as the " +
                "bills - so the next bill carries on from the highest of them rather " +
                "than starting again from your Start No."
        }

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Erase all bills?",
            message = "This throws away all ${preview.bills} bill(s) on this device, along " +
                "with their items, the payments taken against them, their print records " +
                "and their kitchen orders. It cannot be undone." +
                "\n\nA backup is taken first, into Downloads/POSbackup - everything but " +
                "this device's users and store registration, so restoring it later would " +
                "not disturb who can sign in." +
                counter +
                "\n\n• What customers owe is not written off. A credit sale's debt stays " +
                "on the ledger and on the customer, with the bill it came from gone." +
                "\n\n• Stock stays sold. The goods left the shop, so the quantities are " +
                "not put back." +
                "\n\nProducts, customers and every setting are left as they are.",
            positiveText = "Erase Bills",
            negativeText = "Cancel",
            destructive = true
        ) {
            withPassword("erase all bills", "Erase Bills") { runEraseBills() }
        }
    }

    private fun runEraseBills() = inBackground("Backing up, then erasing bills…") {
        val backup = safetyBackup("erase bills") ?: return@inBackground
        val outcome = BillErase.erase(requireContext())
        onMain {
            // The DATA section on this screen counts the records that just went.
            view?.let { showSections(it) }
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Bills erased",
                message = "${outcome.bills} bill(s) erased. The next bill will be " +
                    "numbered ${outcome.nextNumber}." +
                    "\n\nThe till as it was is saved to $backup. It leaves out this " +
                    "device's users and store registration, so restoring it brings the " +
                    "bills back without changing who can sign in."
            )
        }
    }

    // ---- The master tables on their own ---------------------------------------

    /**
     * Writes the catalogue out to Downloads/masterbackup, for another till to load.
     *
     * Off the main thread and streamed, like the database backup: a product master
     * with images in it is not something to assemble in memory on a tablet.
     */
    private fun onExportMasters() = inBackground("Exporting masters…") {
        var summary: DatabaseBackup.Export? = null
        val savedTo = Downloads.stream(
            requireContext(), MasterData.fileName(Date()), "application/sql", MasterData.FOLDER
        ) { writer -> summary = MasterData.exportTo(requireContext(), writer) }
        val export = summary ?: error("nothing was written")

        onMain {
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Masters exported",
                message = "${export.rows} record(s) from ${export.tables} of the " +
                    "${MasterData.TABLES.size} master tables." +
                    "\n\nSaved to $savedTo. It carries no store, so it can be loaded " +
                    "onto any till - restoring stamps it with that device's own store."
            )
        }
    }

    /** Offers the exports this app can still see, and the picker for the rest. */
    private fun chooseMasterExport() {
        val found = BackupFiles.list(requireContext(), MasterData.FOLDER)
        if (found.isEmpty()) {
            runCatching { pickMasters.launch(arrayOf("*/*")) }
                .onFailure { toast("No app on this device can pick a file") }
            return
        }
        val recent = found.take(15)
        val items = recent.map {
            DialogUtils.ListItem(
                title = it.name,
                subtitle = BackupFiles.timeLabel(it.takenAt),
                trailing = BackupFiles.sizeLabel(it.bytes)
            )
        } + DialogUtils.ListItem(
            title = "Choose another file…", subtitle = "Browse the device for an export"
        )
        DialogUtils.showList(
            context = requireContext(),
            title = "Restore masters",
            subtitle = "Pick the export to load",
            items = items
        ) { index ->
            if (index == recent.size) {
                runCatching { pickMasters.launch(arrayOf("*/*")) }
                    .onFailure { toast("No app on this device can pick a file") }
            } else {
                confirmRestoreMasters(recent[index].uri)
            }
        }
    }

    /**
     * Says what loading a catalogue replaces before it replaces it.
     *
     * The warning names the products because that is what an operator will not have
     * thought through: this is a replacement, not a merge, and anything priced or
     * renamed on this till since is written over. Old bills keep their own recorded
     * prices - a bill stores what it charged - so the history does not move under
     * them, but what the till *sells* afterwards is entirely the file's.
     */
    private fun confirmRestoreMasters(uri: Uri) {
        val head = BackupFiles.headOf(requireContext(), uri)
        if (head == null) {
            toast("That file could not be read")
            return
        }
        if (!BackupFiles.looksLikeBackup(head)) {
            toast("That file is not a Synergic POS export")
            return
        }
        // A whole-database backup can be loaded here - only its four master tables
        // are read - but it is worth saying so, since the operator picked it
        // expecting a catalogue.
        val wholeBackup = if (MasterData.looksLikeMasterExport(head)) "" else {
            "\n\nThat file is a full database backup, not a master export. Only the " +
                "master tables will be taken from it; the bills, customers and " +
                "settings in it are ignored."
        }

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Restore master tables?",
            message = "This replaces the products, their categories and rates, the rate " +
                "tiers and the units on this device with the ones in the file. It cannot " +
                "be undone." +
                "\n\nAnything priced, renamed or added here since will be written over. " +
                "Bills already taken keep the prices they were charged at." +
                "\n\nCustomers, bills, stock and settings are left alone." +
                wholeBackup,
            positiveText = "Restore Masters",
            negativeText = "Cancel",
            destructive = true
        ) {
            withPassword("restore the master tables", "Restore Masters") {
                runRestoreMasters(uri)
            }
        }
    }

    private fun runRestoreMasters(uri: Uri) = inBackground("Backing up, then restoring masters…") {
        val backup = safetyBackup("restore master tables") ?: return@inBackground
        val context = requireContext().applicationContext
        val result = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().useLines { lines ->
                MasterData.restore(context, lines, DatabaseBackup.schemaVersionOf(
                    BackupFiles.headOf(context, uri).orEmpty()
                ))
            }
        } ?: MasterData.Result(0, 0, null, "that file could not be opened")

        onMain {
            if (!result.ok) {
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Restore failed",
                    message = "${result.error}\n\nNothing was changed."
                )
                return@onMain
            }
            // Said explicitly: the stamp is what makes a store-less file this shop's,
            // and without a registration there is nothing to stamp it with.
            val stamped = if (result.storeId != null) {
                "\n\nThe catalogue now belongs to store ${result.storeId}."
            } else {
                "\n\nThis device is not registered to a store yet, so the catalogue " +
                    "carries no store and will not show until it is. Restore the " +
                    "masters again once the device is registered."
            }
            view?.let { showSections(it) }
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Masters restored",
                message = "${result.rows} record(s) into ${result.tables} table(s).$stamped" +
                    "\n\nThe catalogue as it was is saved to $backup."
            )
        }
    }

    // ---- Restore defaults -----------------------------------------------------

    /**
     * Says what a reset costs before it costs it.
     *
     * The warning names the two things an operator would not otherwise expect and
     * cannot get back by pressing the button again: the till returns to Grocery -
     * taking the restaurant screens with it - and the printers are forgotten, which
     * means re-pairing hardware, not re-ticking a box. Everything else on the list is
     * a switch that can be put back in a minute.
     *
     * It also says what is *not* touched. A destructive-looking button on the same
     * screen as Backup and Restore invites the reading that it wipes the shop's data,
     * and an operator who thinks that will never press it - or will press it thinking
     * it is a factory wipe and be surprised that their bills are still there.
     */
    private fun confirmRestoreDefaults() {
        val restaurant = SettingsCache.value(requireContext(), "G", "Mode") == "R"
        val modeNote = if (restaurant) {
            "\n\n• This till is in Restaurant mode and will go back to Grocery. KOT, " +
                "tables, sections and waiters disappear from the menu until Restaurant " +
                "is chosen again."
        } else ""

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Restore default settings?",
            message = "Every setting goes back to how the app came - General, Bill, Tax " +
                "and App settings, the print template, the automatic backup and the " +
                "theme colour. This cannot be undone." +
                "\n\nA backup is taken first, into Downloads/POSbackup - everything but " +
                "this device's users and store registration, so restoring it later would " +
                "not disturb who can sign in." +
                modeNote +
                "\n\n• The printers are forgotten. Every named printer is removed and " +
                "the connections go back to WIFI for bills and LAN for KOT, with no " +
                "address saved - each printer has to be set up and paired again." +
                "\n\nYour data is safe: products, customers, bills, stock, the store " +
                "registration, the users and the bill's header, footer and logo are " +
                "all left as they are.",
            positiveText = "Restore Defaults",
            negativeText = "Cancel",
            destructive = true
        ) {
            withPassword("restore the default settings", "Restore Defaults") {
                runRestoreDefaults()
            }
        }
    }

    /** Writes the frozen defaults back, then makes the screen and the app show them. */
    private fun runRestoreDefaults() = inBackground("Backing up, then restoring defaults…") {
        val backup = safetyBackup("restore defaults") ?: return@inBackground
        val outcome = DefaultSettings.restore(requireContext())
        onMain {
            // The theme colour is one of the things that was just reset, so the whole
            // live view tree is re-tinted rather than only this screen - the drawer,
            // the header and the status bar are all still wearing the old accent.
            (activity as? com.example.synergic_pos_offline.MainActivity)?.applyThemeEverywhere()
            view?.let { refreshAfterRestore(it) }

            val printerNote = when (outcome.printersRemoved) {
                0 -> ""
                1 -> "\n\nOne printer was removed and has to be set up again in " +
                    "Settings › Printer Settings."
                else -> "\n\n${outcome.printersRemoved} printers were removed and have to " +
                    "be set up again in Settings › Printer Settings."
            }
            DialogUtils.showSuccess(
                context = requireContext(),
                title = "Defaults restored",
                message = "Every setting is back to how the app came.$printerNote" +
                    "\n\nThe settings as they were are saved to $backup - restoring that " +
                    "file puts them back. It leaves out this device's users and store " +
                    "registration, so restoring it will not change who can sign in."
            )
        }
    }

    /**
     * Re-reads this screen from the settings that were just written.
     *
     * The rows and the auto-backup controls are set from the stored values directly
     * rather than by running [bindAutoBackup] again: that registers a text watcher
     * each time it is called, and this screen can be reset more than once without
     * leaving the app.
     */
    private fun refreshAfterRestore(view: View) {
        showSections(view)   // Mode and Stock tracking are on this screen
        styleButtons(view)

        val settings = AutoBackup.settings(requireContext())
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.swAutoBackup
        ).isChecked = settings.enabled
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etAutoBackupHours
        ).setText(settings.intervalHours.toString())
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.tilAutoBackupHours
        ).error = null
        view.findViewById<View>(R.id.llAutoBackupInterval).visibility =
            if (settings.enabled) View.VISIBLE else View.GONE
        showAutoBackupState(view)
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
        val head = BackupFiles.headOf(requireContext(), uri)
        if (head == null) {
            toast("Could not read that file")
            return
        }
        if (!BackupFiles.looksLikeBackup(head)) {
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

        // A safety backup says in its own header that it does not carry the users or
        // the registration, and this dialog reads it rather than reciting what a
        // whole-database backup would do. Told wrongly that their logins are about to
        // be replaced, an operator restoring their own settings from ten minutes ago
        // has no way to know they can simply carry on.
        val keepsIdentity = DatabaseBackup.excludedIn(head)
            .containsAll(DatabaseBackup.DEVICE_IDENTITY)
        val whatItReplaces = if (keepsIdentity) {
            "This replaces this device's data with the backup - products, customers, " +
                "bills and settings. It cannot be undone." +
                "\n\nThis backup does not carry users or the store registration, so this " +
                "device keeps its own. You will still be signed out; sign back in with " +
                "the login you use here."
        } else {
            "This replaces everything on this device with the backup - products, " +
                "customers, bills, settings, the store registration and the users. It " +
                "cannot be undone." +
                "\n\nYou will be signed out afterwards. Sign back in with the login from " +
                "the device the backup was taken on: this device's own users are replaced " +
                "too."
        }

        DialogUtils.showConfirm(
            context = requireContext(),
            title = "Restore data?",
            message = "$whatItReplaces$mismatch",
            positiveText = "Restore",
            negativeText = "Cancel",
            destructive = true
        ) { runRestore(uri, taken, keepsIdentity) }
    }

    /** Streams the file into the database, off the main thread. */
    private fun runRestore(uri: Uri, schemaVersion: Int?, keepsIdentity: Boolean) =
        inBackground("Restoring…") {
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
                // Signing out is unavoidable either way - the session is a copy taken
                // at login and every screen filters by it - but which login to come
                // back with depends on whether the file carried the users.
                val signIn = if (keepsIdentity) {
                    "\n\nSigning you out so the app reads itself back. Sign in again as " +
                        "you normally do on this device."
                } else {
                    "\n\nSigning you out. Sign back in with the login from the device the " +
                        "backup came from."
                }
                DialogUtils.showSuccess(
                    context = requireContext(),
                    title = "Restored",
                    message = "${result.rows} record(s) into ${result.tables} table(s)." +
                        "$skipped$signIn",
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
     * Shared with the login screen's restore, which has the same problem: both read
     * or write the whole database, which is not main-thread work on a real shop's
     * data. See [BusyDialog].
     */
    private fun inBackground(message: String, work: () -> Unit) =
        BusyDialog.run(this, message, work)

    /** Runs [block] on the main thread, and only while the screen is still there. */
    private fun onMain(block: () -> Unit) = BusyDialog.onMain(this, block)

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
