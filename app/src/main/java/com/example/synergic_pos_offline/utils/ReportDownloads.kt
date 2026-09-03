package com.example.synergic_pos_offline.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import com.example.synergic_pos_offline.R
import com.google.android.material.button.MaterialButton

/**
 * The PDF and Excel buttons on a report screen, wired the same way on every one.
 *
 * The date-range reports get this through their base class; the handful that predate
 * it and stand on their own call [wire] themselves. Either way the rule is the same:
 * the buttons are dead until a report has been generated, and what they write is the
 * report that was generated - never a fresh reading of the database, which could
 * differ from the figures on the screen by the time the file lands.
 */
class ReportDownloads private constructor(
    private val pdf: MaterialButton,
    private val excel: MaterialButton
) {

    /** On once a report is generated, off again when a period turns up nothing. */
    fun setEnabled(enabled: Boolean) {
        listOf(pdf, excel).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    companion object {

        /**
         * Finds the two buttons in [root], dresses them and points them at [sheet].
         *
         * [sheet] is called at the moment of the tap and may return null, which is the
         * screen saying it has nothing generated to write.
         */
        fun wire(
            root: View,
            context: Context,
            accent: Int,
            toast: (String) -> Unit,
            sheet: () -> ReportExport.Sheet?
        ): ReportDownloads {
            val pdf = root.findViewById<MaterialButton>(R.id.btnReportPdf)
            val excel = root.findViewById<MaterialButton>(R.id.btnReportExcel)

            listOf(pdf, excel).forEach { b ->
                // ThemeManager fills every MaterialButton; these are secondary actions
                // beside Generate and keep the outlined look Print has.
                b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                b.setTextColor(accent)
                b.strokeColor = ColorStateList.valueOf(accent)
                // The file icons keep their own colours - red for PDF, green for the
                // spreadsheet - which is what makes them identifiable at a glance.
                b.iconTint = null
            }

            pdf.setOnClickListener { save(context, sheet(), asPdf = true, toast = toast) }
            excel.setOnClickListener { save(context, sheet(), asPdf = false, toast = toast) }

            return ReportDownloads(pdf, excel).also { it.setEnabled(false) }
        }

        private fun save(
            context: Context,
            sheet: ReportExport.Sheet?,
            asPdf: Boolean,
            toast: (String) -> Unit
        ) {
            if (sheet == null) return
            val saved = runCatching {
                if (asPdf) ReportExport.toPdf(context, sheet)
                else ReportExport.toExcel(context, sheet)
            }
            toast(
                saved.fold(
                    onSuccess = { "Saved to $it" },
                    onFailure = { "Could not save the ${if (asPdf) "PDF" else "spreadsheet"}" }
                )
            )
        }
    }
}
