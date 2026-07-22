package com.example.synergic_pos_offline.utils

import android.content.Context
import android.widget.Toast

/**
 * The "WiFi printer" form, shared by every screen that can print.
 *
 * Kept in one place so the till only ever has one idea of what a printer is: the
 * bill screen and checkout both reach it, and a printer set up from one is
 * immediately the printer the other uses.
 */
object PrinterSetup {

    /**
     * Asks for the printer's address, saves it, and hands the config back.
     *
     * The printer's own IP is what is wanted here - most ESC/POS units show it on a
     * self-test slip held down at power-on - and 9100 is the near-universal port.
     *
     * @param onSaved called only when a usable address was entered
     */
    fun show(context: Context, onSaved: (ThermalPrinter.Config) -> Unit) {
        val existing = ThermalPrinter.savedConfig(context)
        DialogUtils.showForm(
            context = context,
            title = "WiFi printer",
            fields = listOf(
                DialogUtils.FormField("Printer IP address", existing?.ip.orEmpty(), inputType = "text"),
                DialogUtils.FormField(
                    "Port", (existing?.port ?: ThermalPrinter.defaultPort()).toString(),
                    inputType = "number"
                ),
                DialogUtils.FormField(
                    "Paper width (mm)",
                    (existing?.paperMm ?: ThermalPrinter.defaultPaperMm()).toString(),
                    inputType = "number"
                )
            ),
            positiveText = "Save & print",
            mandatoryFields = listOf(0),
            onSave = { values ->
                val ip = values.getOrNull(0)?.trim().orEmpty()
                val port = values.getOrNull(1)?.trim()?.toIntOrNull()
                    ?: ThermalPrinter.defaultPort()
                val paperMm = values.getOrNull(2)?.trim()?.toIntOrNull()
                    ?: ThermalPrinter.defaultPaperMm()
                if (ip.isEmpty()) {
                    Toast.makeText(context, "Enter the printer's IP address", Toast.LENGTH_SHORT).show()
                    return@showForm
                }
                val config = ThermalPrinter.Config(ip, port, paperMm)
                ThermalPrinter.saveConfig(context, config)
                onSaved(config)
            }
        )
    }
}
