package com.example.synergic_pos_offline.lanreport

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The live half of Mobile Reports: a socket every connected phone keeps open, down
 * which the till pushes table changes ([TableEvents]) the moment they happen - so a
 * phone's Table Settlement page shows a table billed on the till without a Refresh,
 * and drops one paid at the counter or on another phone.
 *
 * Plain TCP, one JSON object per line - nothing for the till or the phone to install.
 * Port [PORT], beside the HTTP server's (the phone learns it from `/api/ping`).
 *
 * A phone must send its PIN first (`PIN 123456`); anything else is closed, and ten
 * wrong PINs from one address lock it out for a minute, as on the HTTP server. The
 * till sends `{"type":"ping"}` every [HEARTBEAT_MS] so a phone that walked out of
 * Wi-Fi is noticed and dropped, and the phone can tell a quiet till from a dead one.
 *
 * Sends only. Nothing a phone writes after its PIN is acted on.
 */
object EventSocketServer {

    val PORT = LanPairing.PORT + 1

    private const val TAG = "EventSocket"
    private const val HEARTBEAT_MS = 20_000L
    private const val HANDSHAKE_MS = 10_000
    private const val MAX_FAILURES = 10
    private const val LOCKOUT_MS = 60_000L

    private class Client(val socket: Socket, val out: BufferedWriter) {
        @Synchronized fun send(line: String) { out.write(line); out.write("\n"); out.flush() }
        fun close() = runCatching { socket.close() }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val strikes = ConcurrentHashMap<String, Pair<Int, Long>>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var heartbeat: Thread? = null

    /** How many phones are listening right now. */
    val connected: Int get() = clients.size

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val ss = try {
            ServerSocket(PORT).apply { reuseAddress = true }
        } catch (e: IOException) {
            Log.e(TAG, "Event socket could not open port $PORT", e)
            return
        }
        server = ss
        Thread({ acceptLoop(app, ss) }, "MobileReports-events").apply { isDaemon = true; start() }
        heartbeat = Thread({
            while (server === ss) {
                runCatching { Thread.sleep(HEARTBEAT_MS) }.onFailure { return@Thread }
                broadcast(JSONObject().put("type", "ping"))
            }
        }, "MobileReports-heartbeat").apply { isDaemon = true; start() }
        Log.i(TAG, "Table events on port $PORT")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        heartbeat?.interrupt()
        heartbeat = null
        clients.forEach { it.close() }
        clients.clear()
    }

    /**
     * Writes happen here, never on the caller's thread: events are published from the
     * till's screens - Print Bill, Confirm Payment - on the main thread, where Android
     * refuses network I/O outright. One thread, so events reach a phone in the order
     * they happened.
     */
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "MobileReports-sender").apply { isDaemon = true }
    }

    /** Sends [event] to every connected phone, dropping any that has gone away. */
    fun broadcast(event: JSONObject) {
        val line = event.toString()
        sender.execute {
            clients.forEach { c ->
                try {
                    c.send(line)
                } catch (e: Exception) {
                    clients.remove(c)
                    c.close()
                }
            }
        }
    }

    /**
     * The POS user logged out: every phone is told ({"type":"logout"}) and then cut
     * off. Until somebody signs in again the handshake below refuses them, so a phone
     * that tries to come back is told the same thing.
     */
    fun announceLogout() {
        val line = JSONObject().put("type", "logout").toString()
        sender.execute {
            clients.forEach { c ->
                runCatching { c.send(line) }
                clients.remove(c)
                c.close()
            }
        }
    }

    private fun acceptLoop(app: Context, ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (e: IOException) { break }
            Thread({ handshake(app, socket) }, "MobileReports-client").apply { isDaemon = true; start() }
        }
    }

    private fun handshake(app: Context, socket: Socket) {
        val address = socket.inetAddress?.hostAddress.orEmpty()
        try {
            socket.soTimeout = HANDSHAKE_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            if (lockedOut(address)) {
                out.write(JSONObject().put("type", "error").put("error", "Too many wrong PINs - wait a minute").toString() + "\n")
                out.flush(); socket.close(); return
            }
            val pin = reader.readLine()?.trim()?.removePrefix("PIN")?.trim()
            if (!LanPairing.pinMatches(app, pin)) {
                strike(address)
                out.write(JSONObject().put("type", "error").put("error", "Wrong or missing PIN").toString() + "\n")
                out.flush(); socket.close(); return
            }
            strikes.remove(address)
            if (!com.example.synergic_pos_offline.utils.SessionManager.isSignedIn(app)) {
                out.write(JSONObject().put("type", "logout").toString() + "\n")
                out.flush(); socket.close(); return
            }
            val client = Client(socket, out)
            client.send(JSONObject().put("type", "hello").toString())
            clients.add(client)
            // Read until the phone hangs up, so it is dropped as soon as it does. The
            // heartbeat catches the ones that vanish without hanging up.
            socket.soTimeout = 0
            try { while (reader.readLine() != null) Unit } catch (_: IOException) {}
            clients.remove(client)
            client.close()
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    private fun lockedOut(address: String): Boolean {
        val (count, since) = strikes[address] ?: return false
        if (System.currentTimeMillis() - since > LOCKOUT_MS) { strikes.remove(address); return false }
        return count >= MAX_FAILURES
    }

    private fun strike(address: String) {
        val now = System.currentTimeMillis()
        strikes.compute(address) { _, s ->
            if (s == null || now - s.second > LOCKOUT_MS) 1 to now else (s.first + 1) to s.second
        }
    }
}
