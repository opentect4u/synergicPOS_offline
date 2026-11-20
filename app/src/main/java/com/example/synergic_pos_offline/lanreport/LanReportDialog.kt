package com.example.synergic_pos_offline.lanreport

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.synergic_pos_offline.R
import com.example.synergic_pos_offline.utils.DialogUtils
import com.example.synergic_pos_offline.utils.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Settings ▸ Mobile Reports: switches sharing on or off and shows a phone how to
 * connect - the till's address, the PIN, and a QR code carrying both.
 *
 * Admin only; the Settings tile is hidden from other logins.
 */
object LanReportDialog {

    fun show(activity: Activity) {
        // The app's global dialog chrome - fixed font scale, transparent window,
        // centred card, narrowed to fit a small screen. See DialogUtils.buildCustom.
        val (dialog, view) = DialogUtils.buildCustom(activity, R.layout.dialog_mobile_reports)
        val ctx = view.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val accent = ThemeManager.getThemeColor(activity)

        val swShare = view.findViewById<SwitchMaterial>(R.id.swShare)
        val tvShareState = view.findViewById<TextView>(R.id.tvShareState)
        val llPairing = view.findViewById<View>(R.id.llPairing)
        val tvAddress = view.findViewById<TextView>(R.id.tvAddress)
        val tvPin = view.findViewById<TextView>(R.id.tvPin)
        val ivQr = view.findViewById<ImageView>(R.id.ivQr)
        val tvHelp = view.findViewById<TextView>(R.id.tvHelp)
        val btnNewPin = view.findViewById<MaterialButton>(R.id.btnNewPin)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)

        // Same accent treatment as every other dialog: filled Close, outlined New PIN.
        ThemeManager.styleDialogButtons(btnClose, btnNewPin, accent)
        swShare.thumbTintList = ColorStateList.valueOf(accent)

        fun refresh() {
            val on = LanPairing.isEnabled(ctx)
            swShare.isChecked = on
            val ip = LanPairing.localIp(ctx)
            val pin = LanPairing.pin(ctx)
            btnNewPin.isEnabled = on
            btnNewPin.alpha = if (on) 1f else 0.45f
            when {
                !on -> {
                    tvShareState.text = "Off - phones cannot read reports"
                    llPairing.visibility = View.GONE
                }
                ip == null -> {
                    tvShareState.text = "On"
                    llPairing.visibility = View.VISIBLE
                    tvAddress.text = "Not connected to Wi-Fi or LAN - connect the till to the shop network."
                    tvPin.text = "PIN  $pin"
                    ivQr.visibility = View.GONE
                    tvHelp.visibility = View.GONE
                }
                else -> {
                    tvShareState.text = if (LanReportService.running) "On - phones on this network can connect" else "Starting…"
                    llPairing.visibility = View.VISIBLE
                    tvAddress.text = "Address  $ip:${LanPairing.PORT}"
                    tvPin.text = "PIN  $pin"
                    ivQr.visibility = View.VISIBLE
                    ivQr.setImageBitmap(qr(LanPairing.qrPayload(ip, pin), dp(220)))
                    tvHelp.visibility = View.VISIBLE
                }
            }
        }

        swShare.setOnCheckedChangeListener { _, checked ->
            if (checked == LanPairing.isEnabled(ctx)) return@setOnCheckedChangeListener
            LanPairing.setEnabled(ctx, checked)
            if (checked) {
                askNotificationPermission(activity)
                LanReportService.start(ctx)
            } else {
                LanReportService.stop(ctx)
            }
            refresh()
            // The server takes a moment to come up; "Starting…" until it has. Checked
            // every half second for up to ten seconds, then shown as it stands.
            if (checked) {
                val handler = Handler(Looper.getMainLooper())
                var tries = 0
                handler.post(object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing) return
                        refresh()
                        if (!LanReportService.running && LanPairing.isEnabled(ctx) && ++tries < 20) {
                            handler.postDelayed(this, 500)
                        }
                    }
                })
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        // Kept open after a new PIN: it is something to see, then scan.
        btnNewPin.setOnClickListener {
            DialogUtils.showConfirm(
                context = activity,
                title = "Make a new PIN?",
                message = "Every phone paired with the current PIN stops getting reports until it scans the new code.",
                positiveText = "New PIN"
            ) {
                LanPairing.newPin(ctx)
                refresh()
            }
        }
        refresh()
        dialog.show()
    }

    private fun askNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8765)
        }
    }

    private fun qr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
