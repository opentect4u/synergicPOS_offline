package com.example.synergic_pos_offline.fragments

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.OperatingPrinterDao
import com.example.synergic_pos_offline.database.PrinterDao
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * "Operating Printer" master screen - the [DatabaseHelper.Tables.MD_OPERATING_PRINTER]
 * counterpart of [PrinterSettingsFragment]'s BILL/KOT/OTHERS picker, rebuilt as
 * the standard [DataTableFragment] master: a searchable list with a "+" button
 * whose Add/Edit popup names a printer, picks a BILL/KOT connection from
 * [DatabaseHelper.Tables.MD_PRINTER] (shown as "BILL-WIFI", "KOT-BLUETOOTH",
 * etc., but saved as that row's sl_no), asks for the address that connection
 * needs - discovering and pairing a brand-new Bluetooth device in-app when
 * that's the chosen connection - and lets it be marked the default printer
 * for its purpose; [com.example.synergic_pos_offline.utils.ThermalPrinter]
 * prints BILL/KOT jobs through whichever row is marked default here.
 */
class OperatingPrinterFragment : DataTableFragment() {

    override val screenTitle = "Operating Printer"

    // Table columns. Cell layout per row: [name, printer, value, paper, flag, default].
    override val columns = listOf("Printer Name", "Printer", "Value", "Paper", "Flag", "Default")

    // The Default column renders as an inline ON/OFF switch.
    override val switchColumn: Int? = COL_DEFAULT

    private companion object {
        const val COL_NAME = 0
        const val COL_PRINTER = 1
        const val COL_VALUE = 2
        const val COL_PAPER = 3
        const val COL_FLAG = 4
        const val COL_DEFAULT = 5
    }

    private val dao: OperatingPrinterDao by lazy { OperatingPrinterDao(requireContext()) }
    private val printerDao: PrinterDao by lazy { PrinterDao(requireContext()) }

    /** Full entries keyed by sl_no (as string), for edit prefill. */
    private val entryCache = mutableMapOf<String, OperatingPrinterDao.OperatingPrinter>()

    /** Bluetooth picker labels ("Name (AA:BB:..)") mapped to the device address. */
    private val btLabelToAddress = mutableMapOf<String, String>()

    // The Bluetooth action (scan, pair, list) to resume once the runtime
    // permission prompt returns.
    private var pendingBtAction: (() -> Unit)? = null
    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = pendingBtAction
            pendingBtAction = null
            if (results.values.all { it }) action?.invoke()
            else toast("Bluetooth permission is needed to pair a printer")
        }

    /**
     * CONNECT+SCAN are the runtime permissions on Android 12+; on older versions
     * discovery instead needs location, and SCAN/CONNECT are install-time grants.
     */
    private fun requiredBtPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBtPermissions(): Boolean =
        requiredBtPermissions().all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    /** Runs [action] once the permissions Bluetooth scanning/pairing need are granted. */
    private fun withBtPermissions(action: () -> Unit) {
        if (hasBtPermissions()) { action(); return }
        pendingBtAction = action
        btPermissionLauncher.launch(requiredBtPermissions())
    }

    /** BILL/KOT purpose-type combos from md_printer, e.g. "BILL-WIFI" -> that md_printer row (its sl_no is what gets saved). */
    private fun loadCombos(): LinkedHashMap<String, PrinterDao.Printer> =
        LinkedHashMap<String, PrinterDao.Printer>().apply {
            printerDao.getAll()
                .filter { it.purpose.equals("BILL", true) || it.purpose.equals("KOT", true) }
                .forEach { put("${it.purpose.uppercase()}-${it.type.uppercase()}", it) }
        }

    // ---- Data --------------------------------------------------------------

    override fun loadRows(): MutableList<DataRow> {
        entryCache.clear()
        val entries = dao.getAll()
        for (e in entries) entryCache[e.slNo.toString()] = e
        return entries.map { it.toRow() }.toMutableList()
    }

    private fun OperatingPrinterDao.OperatingPrinter.toRow(): DataRow = DataRow(
        slNo.toString(),
        listOf(
            printerName, printerLabel.ifBlank { "—" }, value.orEmpty().ifBlank { "—" },
            paperLabel, printFlag, if (isDefault) "On" else "Off"
        )
    )

    // ---- Custom Add / Edit popups -------------------------------------------

    override fun onAddRow() = showPrinterDialog(null)

    override fun onEditRow(row: DataRow) = showPrinterDialog(row)

    override fun onRowsDeleted(ids: Set<String>) {
        dao.delete(ids.mapNotNull { it.toLongOrNull() })
    }

    /** Inline row switch flips the default flag directly, without opening the form. */
    override fun onSwitchToggled(row: DataRow, isOn: Boolean) {
        val flag = row.cells.getOrNull(COL_FLAG).orEmpty()
        dao.setDefault(row.id.toLong(), flag, isOn)
        reload()
    }

    private fun showPrinterDialog(row: DataRow?) {
        val ctx = requireContext()
        val accent = ThemeManager.getThemeColor(ctx)
        val existing = row?.let { entryCache[it.id] }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_operating_printer, null)
        val dialog = AlertDialog.Builder(ctx).setView(view).create().also { it.setCanceledOnTouchOutside(false) }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val etName = view.findViewById<TextInputEditText>(R.id.etPrinterName)
        val actvCombo = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvPrinterCombo)
        val tilIp = view.findViewById<TextInputLayout>(R.id.tilIpAddress)
        val etIp = view.findViewById<TextInputEditText>(R.id.etIpAddress)
        val tilBt = view.findViewById<TextInputLayout>(R.id.tilBtDevice)
        val actvBt = view.findViewById<MaterialAutoCompleteTextView>(R.id.actvBtDevice)
        val tvUsbNote = view.findViewById<TextView>(R.id.tvUsbNote)
        val rb58mm = view.findViewById<RadioButton>(R.id.rb58mm)
        val rb80mm = view.findViewById<RadioButton>(R.id.rb80mm)
        val swDefault = view.findViewById<SwitchMaterial>(R.id.swDefault)
        val tvDefaultState = view.findViewById<TextView>(R.id.tvDefaultState)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnFormPositive)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnFormNegative)

        val comboMap = loadCombos()
        val comboOptions = comboMap.keys.toList()
        actvCombo.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, comboOptions))

        // actvBt intentionally has no adapter of its own: tapping it opens the
        // scan/pair dialog below instead of a plain dropdown list.
        actvBt.setOnClickListener { showBluetoothPickerDialog(actvBt) }

        // Toggles which address field is shown for the chosen combo's connection type.
        fun showFieldsFor(type: String?) {
            tilIp.visibility = View.GONE
            tilBt.visibility = View.GONE
            tvUsbNote.visibility = View.GONE
            when (type) {
                "WIFI", "LAN" -> tilIp.visibility = View.VISIBLE
                "BLUETOOTH" -> tilBt.visibility = View.VISIBLE
                "USB" -> tvUsbNote.visibility = View.VISIBLE
            }
        }

        actvCombo.setOnItemClickListener { _, _, position, _ ->
            val type = comboMap[comboOptions.getOrNull(position)]?.type?.uppercase()
            showFieldsFor(type)
            // A freshly chosen combo has nothing selected yet; tapping the field opens the picker.
            if (type == "BLUETOOTH") actvBt.setText("", false)
        }

        tvTitle.text = if (existing == null) "Add Operating Printer" else "Edit Operating Printer"
        etName.setText(existing?.printerName.orEmpty())
        // The saved row only carries the md_printer sl_no; find the combo label
        // whose row matches it so the dropdown can preselect it.
        val initialCombo = existing?.let { e -> comboMap.entries.firstOrNull { it.value.slNo == e.printerSlNo }?.key }
        val initialType = initialCombo?.let { comboMap[it]?.type?.uppercase() }
        if (initialCombo != null) actvCombo.setText(initialCombo, false)
        showFieldsFor(initialType)
        if (initialType == "WIFI" || initialType == "LAN") etIp.setText(existing?.value.orEmpty())
        if (initialType == "BLUETOOTH") prefillBtField(actvBt, existing?.value)
        if ((existing?.paperMm ?: OperatingPrinterDao.DEFAULT_PAPER_MM) == 58) rb58mm.isChecked = true else rb80mm.isChecked = true
        swDefault.isChecked = existing?.isDefault ?: false
        tvDefaultState.text = if (swDefault.isChecked) "Default" else "Not default"
        btnSave.text = if (existing == null) "Add" else "Update"

        swDefault.setOnCheckedChangeListener { _, checked ->
            tvDefaultState.text = if (checked) "Default" else "Not default"
        }

        ThemeManager.applyTheme(view)
        swDefault.thumbTintList = ColorStateList.valueOf(accent)
        btnSave.backgroundTintList = ColorStateList.valueOf(accent)
        // ThemeManager fills every MaterialButton's background; restore the
        // outlined (border) look for the negative/Cancel button.
        btnCancel.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btnCancel.setTextColor(accent)
        btnCancel.strokeColor = ColorStateList.valueOf(accent)

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                etName.error = "Printer name is required"
                return@setOnClickListener
            }

            val combo = actvCombo.text?.toString()?.trim().orEmpty()
            val printer = comboMap[combo]
            if (printer == null) {
                toast("Choose a printer from the list")
                return@setOnClickListener
            }
            val type = printer.type.uppercase()

            val value: String? = when (type) {
                "WIFI", "LAN" -> {
                    val ip = etIp.text?.toString()?.trim().orEmpty()
                    if (ip.isEmpty()) {
                        etIp.error = "Enter the printer's IP address"
                        return@setOnClickListener
                    }
                    ip
                }
                "BLUETOOTH" -> {
                    val label = actvBt.text?.toString()?.trim().orEmpty()
                    val address = btLabelToAddress[label]
                    if (address.isNullOrBlank()) {
                        toast("Pick or pair a Bluetooth device")
                        return@setOnClickListener
                    }
                    address
                }
                else -> null
            }

            val isDefault = swDefault.isChecked
            val paperMm = if (rb58mm.isChecked) 58 else 80

            if (existing == null) {
                val id = dao.insert(name, printer.slNo, printer.purpose, value, paperMm, isDefault)
                if (id == -1L) { toast("Save failed"); return@setOnClickListener }
                dialog.dismiss()
                reload()
                toast("Added $name")
            } else {
                dao.update(existing.slNo, name, printer.slNo, printer.purpose, value, paperMm, isDefault)
                dialog.dismiss()
                reload()
                toast("Updated $name")
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }

    /**
     * Shows the address already saved in [actvBt] without prompting for
     * permission or scanning - resolved to a friendly name only when the
     * device happens to already be bonded and the permission is already held.
     */
    private fun prefillBtField(actvBt: MaterialAutoCompleteTextView, address: String?) {
        if (address.isNullOrBlank()) return
        val ctx = requireContext()
        val bonded = try {
            if (hasBtPermissions() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter?.bondedDevices?.firstOrNull { it.address == address }
            } else null
        } catch (_: SecurityException) {
            null
        }
        val label = if (bonded != null) "${safeName(bonded)}  ($address)" else address
        btLabelToAddress[label] = address
        actvBt.setText(label, false)
    }

    /**
     * Lets the operator pick an already-paired printer or pair a brand-new one,
     * all in-app: lists bonded devices immediately, starts discovery for
     * anything nearby, and pairs on tap - no trip to Android's Bluetooth
     * settings needed. Selecting a bonded device fills [actvBt] and closes.
     */
    private fun showBluetoothPickerDialog(actvBt: MaterialAutoCompleteTextView) {
        withBtPermissions {
            val ctx = requireContext()
            val btAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (btAdapter == null) {
                toast("Bluetooth is not available on this device")
                return@withBtPermissions
            }
            if (!btAdapter.isEnabled) {
                toast("Turn on Bluetooth, then try again")
                return@withBtPermissions
            }

            // address -> (name, isBonded); bonded devices show immediately, scanning adds the rest.
            val devices = linkedMapOf<String, Pair<String, Boolean>>()
            try {
                btAdapter.bondedDevices?.forEach { devices[it.address] = safeName(it) to true }
            } catch (_: SecurityException) {
                // Permission was just confirmed by withBtPermissions; nothing to recover from here.
            }

            val items = mutableListOf<String>()
            fun itemLabel(address: String, name: String, bonded: Boolean) =
                if (bonded) "$name  ($address)\nPaired - tap to select" else "$name  ($address)\nTap to pair"
            fun rebuildItems() {
                items.clear()
                devices.forEach { (address, info) -> items.add(itemLabel(address, info.first, info.second)) }
            }
            rebuildItems()
            val listAdapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, items)

            val density = resources.displayMetrics.density
            val statusText = TextView(ctx).apply {
                text = "Scanning for nearby printers…"
                textSize = 13f
                setPadding(0, 0, 0, (10 * density).toInt())
            }
            val listView = ListView(ctx).apply {
                adapter = listAdapter
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * density).toInt())
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * density).toInt()
                setPadding(pad, pad, pad, 0)
                addView(statusText)
                addView(listView)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.btDeviceExtra() ?: return
                            if (!devices.containsKey(device.address)) {
                                devices[device.address] = safeName(device) to false
                                rebuildItems()
                                listAdapter.notifyDataSetChanged()
                            }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            statusText.text = "Scan finished. Tap a device to select or pair it."
                        }
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device = intent.btDeviceExtra() ?: return
                            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                                BluetoothDevice.BOND_BONDED -> {
                                    devices[device.address] = safeName(device) to true
                                    rebuildItems()
                                    listAdapter.notifyDataSetChanged()
                                    statusText.text = "Paired with ${safeName(device)}"
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    statusText.text = "Pairing with ${safeName(device)} failed"
                                }
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            try {
                btAdapter.startDiscovery()
            } catch (_: SecurityException) {
                statusText.text = "Showing paired devices only (scan permission denied)"
            }

            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Bluetooth Printer")
                .setView(container)
                .setPositiveButton("Done", null)
                .setOnDismissListener {
                    runCatching { btAdapter.cancelDiscovery() }
                    runCatching { ctx.unregisterReceiver(receiver) }
                }
                .create()

            listView.setOnItemClickListener { _, _, position, _ ->
                val entry = devices.entries.elementAtOrNull(position) ?: return@setOnItemClickListener
                val (address, info) = entry
                val (name, bonded) = info
                if (bonded) {
                    val label = "$name  ($address)"
                    btLabelToAddress[label] = address
                    actvBt.setText(label, false)
                    dialog.dismiss()
                } else {
                    statusText.text = "Pairing with $name…"
                    try {
                        btAdapter.getRemoteDevice(address).createBond()
                    } catch (_: SecurityException) {
                        toast("Pairing needs the Bluetooth permission")
                    } catch (_: IllegalArgumentException) {
                        toast("Could not pair with $name")
                    }
                }
            }

            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** The device extra on a Bluetooth broadcast, across API levels. */
    @Suppress("DEPRECATION")
    private fun Intent.btDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** A device's name, or its address when the name can't be read without permission. */
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
}
