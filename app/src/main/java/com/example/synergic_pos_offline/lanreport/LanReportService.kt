package com.example.synergic_pos_offline.lanreport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.synergic_pos_offline.MainActivity

/**
 * Keeps the Mobile Reports server ([ReportServer]) running while sharing is on.
 *
 * A foreground service, so it carries on when the till moves between screens or the
 * screen goes off, with a notification saying reports are being shared. It also:
 *  - holds a Wi-Fi lock and a partial wake lock, so the till still answers a phone
 *    after the screen has been off for a while;
 *  - announces the till on the network (NSD / mDNS, [LanPairing.SERVICE_TYPE]) so a
 *    paired phone can find it again if its IP address changes.
 *
 * Works the same over Wi-Fi or a LAN cable: the server listens on every interface.
 */
class LanReportService : Service() {

    private var server: ReportServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LanPairing.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) startServer()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        true
    } catch (e: Exception) {
        // Android refuses a foreground service started from the background on 12+;
        // the next time the till is opened starts it again.
        Log.w(TAG, "Could not start Mobile Reports in the foreground", e)
        false
    }

    private fun startServer() {
        val s = ReportServer(this)
        try {
            s.start(SOCKET_TIMEOUT_MS, false)
        } catch (e: Exception) {
            Log.e(TAG, "Mobile Reports server failed to start on port ${LanPairing.PORT}", e)
            stopSelf()
            return
        }
        server = s
        running = true
        // The live table events phones listen on - see EventSocketServer.
        EventSocketServer.start(this)
        acquireLocks()
        announce()
        Log.i(TAG, "Mobile Reports sharing on port ${LanPairing.PORT}")
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SynergicPOS:MobileReports")
                .apply { setReferenceCounted(false); acquire() }
        }
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SynergicPOS:MobileReports")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun announce() {
        runCatching {
            val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "SynergicPOS"
                serviceType = LanPairing.SERVICE_TYPE
                port = LanPairing.PORT
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Announced as ${info.serviceName}") }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "Announce failed: $code") }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            nsdListener = listener
        }.onFailure { Log.w(TAG, "Network announce unavailable", it) }
    }

    override fun onDestroy() {
        running = false
        runCatching { nsdListener?.let { nsdManager?.unregisterService(it) } }
        runCatching { server?.stop() }
        runCatching { EventSocketServer.stop() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        server = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mobile Reports", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while phones on the shop network can read reports"
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ip = LanPairing.localIp(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Mobile Reports is on")
            .setContentText(if (ip != null) "Phones on this network: $ip:${LanPairing.PORT}" else "Waiting for Wi-Fi or LAN")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "LanReportService"
        private const val CHANNEL_ID = "mobile_reports"
        private const val NOTIFICATION_ID = 8765
        private const val SOCKET_TIMEOUT_MS = 15_000

        /** Whether the server is up right now. */
        @Volatile var running = false
            private set

        /** Starts sharing if it was left switched on. Called as the till opens. */
        fun startIfEnabled(context: Context) {
            if (LanPairing.isEnabled(context)) start(context)
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LanReportService::class.java))
            }.onFailure { Log.w(TAG, "Could not start Mobile Reports", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanReportService::class.java))
        }
    }
}
