package com.example.synergic_pos_offline.fragments

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.database.PrinterDao
import com.example.synergic_pos_offline.utils.PrintLog
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Printer Settings: one card per print purpose (BILL / KOT / OTHERS). Each has a
 * dropdown to choose which connection to use (WIFI / LAN / BLUETOOTH / USB), and
 * tapping the card configures that connection - an IP for WIFI/LAN, or a paper
 * width plus a (not-yet-wired) connect step for BLUETOOTH/USB.
 */
class PrinterSettingsFragment : Fragment(), TitledScreen {

    override val screenTitle = "Printer Settings"

    private val purposes = listOf("BILL", "KOT", "OTHERS")

    private lateinit var llPurposes: LinearLayout
    private lateinit var dao: PrinterDao

    // The printer whose Bluetooth device we are picking, remembered across the
    // runtime permission prompt.
    private var pendingBtPrinter: PrinterDao.Printer? = null
    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val printer = pendingBtPrinter
            pendingBtPrinter = null
            when {
                granted && printer != null -> showBluetoothDeviceDialog(printer)
                !granted -> toast("Bluetooth permission is needed to connect a printer")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_printer_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dao = PrinterDao(requireContext())
        llPurposes = view.findViewById(R.id.llPurposes)
        renderPurposes()
        view.findViewById<MaterialButton>(R.id.btnViewPrintLog).setOnClickListener { showPrintLog() }
        ThemeManager.applyTheme(view)
    }

    /**
     * Shows the on-device print log as selectable, copyable text - the way to
     * diagnose a print failure on a till with no adb/logcat access: read what
     * actually happened here, or copy it out and send it elsewhere.
     */
    private fun showPrintLog() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val logText = TextView(ctx).apply {
            text = PrintLog.read(ctx)
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(ctx).apply { addView(logText) }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Print log")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Print log", logText.text))
                toast("Copied - paste it wherever you need to send it")
            }
            .setNeutralButton("Clear") { _, _ ->
                PrintLog.clear(ctx)
                toast("Log cleared")
            }
            .setNegativeButton("Close", null)
            .show()

        // A tall log needs the dialog to actually use the screen height, or the
        // ScrollView collapses to a sliver.
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.75).toInt()
        )
    }

    private fun renderPurposes() {
        llPurposes.removeAllViews()
        purposes.forEach { addPurposeCard(it) }
    }

    private fun addPurposeCard(purpose: String) {
        val card = layoutInflater.inflate(R.layout.item_printer_purpose, llPurposes, false)
        val tvPurpose = card.findViewById<TextView>(R.id.tvPurpose)
        val tvStatus = card.findViewById<TextView>(R.id.tvStatus)
        val spinner = card.findViewById<Spinner>(R.id.spType)

        tvPurpose.text = purpose

        val options = dao.typesFor(purpose)
        val types = options.map { it.type }
        spinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, types
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val selIndex = types.indexOf(dao.getSelected(purpose)?.type).coerceAtLeast(0)
        spinner.setSelection(selIndex, false)
        tvStatus.text = statusText(options.getOrNull(selIndex))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val chosen = types[position]
                if (chosen != dao.getSelected(purpose)?.type) dao.setSelectedType(purpose, chosen)
                tvStatus.text = statusText(dao.typesFor(purpose).firstOrNull { it.type == chosen })
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        card.setOnClickListener {
            dao.getSelected(purpose)?.let { configure(it) }
        }

        llPurposes.addView(card)
    }

    /** The line under the dropdown describing the selected connection's config. */
    private fun statusText(p: PrinterDao.Printer?): String = when (p?.type?.uppercase()) {
        null -> "Tap to configure"
        "WIFI", "LAN" ->
            if (!p.ip.isNullOrBlank()) "IP: ${p.ip}" + (p.paperMm?.let { "  ·  $it mm" } ?: "")
            else "Tap to set IP address"
        "BLUETOOTH" ->
            if (!p.ip.isNullOrBlank()) "Paired: ${p.ip}" + (p.paperMm?.let { "  ·  $it mm" } ?: "")
            else "Tap to pair a device"
        "USB" ->
            p.paperMm?.let { "$it mm  ·  tap to connect" } ?: "Tap to connect"
        else -> "Tap to configure"
    }

    /** Routes the selected connection to the prompt its type needs. */
    private fun configure(printer: PrinterDao.Printer) {
        when (printer.type.uppercase()) {
            "WIFI", "LAN" -> askForIp(printer)
            "BLUETOOTH" -> askBluetooth(printer)
            "USB" -> askConnect(printer, "USB")
            else -> askForIp(printer)
        }
    }

    // ---- Bluetooth ---------------------------------------------------------

    /** Checks the runtime permission, then shows the paired-device picker. */
    private fun askBluetooth(printer: PrinterDao.Printer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBtPrinter = printer
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        showBluetoothDeviceDialog(printer)
    }

    /** Lists paired Bluetooth printers; saving stores the device MAC + paper width. */
    private fun showBluetoothDeviceDialog(printer: PrinterDao.Printer) {
        val ctx = requireContext()
        val btAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter == null) {
            toast("Bluetooth is not available on this device")
            return
        }
        if (!btAdapter.isEnabled) {
            toast("Turn on Bluetooth, then try again")
            return
        }

        val devices = try {
            btAdapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            toast("No paired Bluetooth printers. Pair one in Android's Bluetooth settings first.")
            return
        }

        val deviceSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_item,
                devices.map { "${safeName(it)}  (${it.address})" }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val savedIndex = devices.indexOfFirst { it.address == printer.ip }
            if (savedIndex >= 0) setSelection(savedIndex)
        }

        val rb58 = RadioButton(ctx).apply { id = View.generateViewId(); text = "58 mm" }
        val rb80 = RadioButton(ctx).apply { id = View.generateViewId(); text = "80 mm" }
        val container = paperContainer(ctx, printer, rb58, rb80, header = deviceSpinner)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("${printer.purpose} printer (Bluetooth)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val device = devices[deviceSpinner.selectedItemPosition]
                dao.updateConfig(printer.slNo, device.address, if (rb58.isChecked) 58 else 80)
                renderPurposes()
                toast("Bluetooth printer saved: ${safeName(device)}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A device's name, or its address when the name can't be read without permission. */
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    /** Asks for the printer's IP address and paper width (58/80 mm), then saves both. */
    private fun askForIp(printer: PrinterDao.Printer) {
        val ctx = requireContext()
        val ipInput = EditText(ctx).apply {
            hint = "IP address"
            setText(printer.ip.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val rb58 = RadioButton(ctx).apply { id = View.generateViewId(); text = "58 mm" }
        val rb80 = RadioButton(ctx).apply { id = View.generateViewId(); text = "80 mm" }
        val container = paperContainer(ctx, printer, rb58, rb80, header = ipInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("${printer.purpose} printer (${printer.type.uppercase()})")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val ip = ipInput.text?.toString()?.trim().orEmpty()
                if (ip.isEmpty()) {
                    Toast.makeText(ctx, "Enter the printer's IP address", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dao.updateConfig(printer.slNo, ip, if (rb58.isChecked) 58 else 80)
                renderPurposes()
                Toast.makeText(ctx, "${printer.purpose} printer saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Bluetooth/USB: lets the paper width be chosen and saved now; the actual
     * pairing/connection is left for later (no SDK wired up yet).
     */
    private fun askConnect(printer: PrinterDao.Printer, label: String) {
        val ctx = requireContext()
        val message = TextView(ctx).apply {
            text = "Connect the $label printer for ${printer.purpose}?"
        }
        val rb58 = RadioButton(ctx).apply { id = View.generateViewId(); text = "58 mm" }
        val rb80 = RadioButton(ctx).apply { id = View.generateViewId(); text = "80 mm" }
        val container = paperContainer(ctx, printer, rb58, rb80, header = message)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("${printer.purpose} printer ($label)")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                dao.updatePaper(printer.slNo, if (rb58.isChecked) 58 else 80)
                renderPurposes()
                Toast.makeText(ctx, "$label connection coming soon", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A dialog body: [header] view, a "Paper width" label, then the 58/80 radios. */
    private fun paperContainer(
        ctx: android.content.Context,
        printer: PrinterDao.Printer,
        rb58: RadioButton,
        rb80: RadioButton,
        header: View
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val paperLabel = TextView(ctx).apply {
            text = "Paper width"
            setPadding(0, (16 * density).toInt(), 0, (6 * density).toInt())
        }
        val paperGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rb58)
            addView(rb80)
        }
        (if (printer.paperMm == 58) rb58 else rb80).isChecked = true

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(header)
            addView(paperLabel)
            addView(paperGroup)
        }
    }
}
