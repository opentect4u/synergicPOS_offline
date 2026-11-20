package com.synergic.posreports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * First run (or after Disconnect): connect this phone to the POS. Scanning the QR
 * code on the POS's Settings ▸ Mobile Reports screen is the quick way; the address and
 * PIN can also be typed, and "Find automatically" looks for the POS on the Wi-Fi.
 */
@Composable
fun PairScreen(
    api: PosApi, store: ConnectionStore,
    /** Why the phone is here, e.g. the POS user logged out; null on a first run. */
    notice: String? = null,
    onPaired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(store.load()?.ip.orEmpty()) }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect(c: Connection) {
        error = null
        busy = "Connecting to ${c.ip}…"
        scope.launch {
            try {
                api.verify(c)
                store.save(c)
                onPaired()
            } catch (e: PosException) {
                error = e.message
            } catch (e: Exception) {
                error = "Cannot reach ${c.ip}. Check the phone is on the same Wi-Fi as the POS and Mobile Reports is on."
            } finally {
                busy = null
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val c = Connection.fromQr(text)
        if (c == null) error = "That is not a SynergicPOS code. Scan the code on the POS's Mobile Reports screen."
        else { ip = c.ip; pin = c.pin; connect(c) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("SynergicPOS Reports", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TealDark)
        // Disconnected rather than never connected: say why, and offer the same POS
        // back in one tap - its address and PIN are still remembered.
        val saved = store.load()
        if (notice != null) {
            WhiteCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Disconnected", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                    Text(notice, color = Muted)
                    if (saved != null) {
                        Button(
                            onClick = { connect(saved) }, enabled = busy == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Reconnect to ${saved.ip}") }
                    }
                }
            }
        }
        Text(
            "See your shop's reports on this phone. Connect the phone to the same Wi-Fi as the POS " +
                "(or the same router as its LAN cable), then on the POS open Settings ▸ Mobile Reports and switch it on.",
            color = Muted
        )

        Button(
            onClick = {
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the QR code on the POS's Mobile Reports screen")
                        .setBeepEnabled(false).setOrientationLocked(false)
                )
            },
            enabled = busy == null,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Scan QR code", fontSize = 16.sp) }

        Text("Or enter it yourself", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = ip, onValueChange = { ip = it.trim() }, label = { Text("POS address (e.g. 192.168.1.20)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                error = null
                busy = "Looking for the POS on this Wi-Fi…"
                scope.launch {
                    val found = Discovery.find(context)
                    busy = null
                    if (found != null) ip = found.first
                    else error = "No POS found. Make sure Mobile Reports is on at the POS, or type its address."
                }
            },
            enabled = busy == null, modifier = Modifier.fillMaxWidth()
        ) { Text("Find automatically") }
        OutlinedTextField(
            value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) }, label = { Text("6-digit PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                when {
                    !Connection.isIp(ip) -> error = "Enter the POS address like 192.168.1.20"
                    !Connection.isPin(pin) -> error = "Enter the 6-digit PIN shown on the POS"
                    else -> connect(Connection(ip, Connection.DEFAULT_PORT, pin))
                }
            },
            enabled = busy == null, modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Connect") }

        busy?.let {
            Column { CircularProgressIndicator(Modifier.size(28.dp)); Text(it, color = Muted) }
        }
        error?.let { Text(it, color = Color(0xFFC62828)) }
    }
}
