package com.synergic.posreports

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** A customer as the checkout's credit panel needs them. */
private class Cust(
    val name: String, val phone: String, val creditEnabled: Boolean,
    val creditLimit: Double, val balance: Double
)

private fun JSONObject.toCust() = Cust(
    optString("name"), optString("phone"), optBoolean("creditEnabled"),
    optDouble("creditLimit", 0.0), optDouble("balance", 0.0)
)

/**
 * The till's dine-in checkout (RestaurantCheckoutFragment), on the phone: the order
 * and its breakdown, the payment tiles, Amount Tendered and Change Due, Part Payment
 * across cash / UPI / card, the scan-to-pay code for Online, the Credit panel, Receipt
 * Preview, and Confirm Payment for the total. The till does the settling - this
 * screen only says how the bill was paid.
 */
@Composable
fun TableCheckoutScreen(
    api: PosApi, orderId: Long, onBack: () -> Unit, onSettled: () -> Unit, onUnauthorized: () -> Unit
) {
    var retry by remember { mutableIntStateOf(0) }
    Loading(orderId, retry, refresh = 0, onUnauthorized = onUnauthorized, load = { api.tableCheckout(orderId) }) { d ->
        CheckoutBody(api, d, onBack, onSettled, onUnauthorized)
    }
}

@Composable
private fun CheckoutBody(
    api: PosApi, d: JSONObject, onBack: () -> Unit, onSettled: () -> Unit, onUnauthorized: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orderId = d.optLong("orderId")
    val total = d.optDouble("total")
    val modes = d.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        .ifEmpty { listOf("Cash") }
    val customers = remember(d) { d.optJSONArray("customers")?.objects().orEmpty().map { it.toCust() } }
    val tableCustomer = d.optJSONObject("customer")?.toCust()
    val upiId = d.optString("upiId").takeIf { it.isNotBlank() && it != "null" }
    val upiPayee = d.optString("upiPayee")

    var mode by remember { mutableStateOf(modes.first()) }
    // Amount Tendered starts on the total - exact payment is one tap (fillTenderedWithTotal).
    var tendered by remember { mutableStateOf(editable(total)) }
    var splitOn by remember { mutableStateOf(false) }
    val split = remember { SplitState(total) }
    var creditCust by remember { mutableStateOf(tableCustomer) }
    var paidNow by remember { mutableStateOf("") }
    var pickCustomer by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val credit = mode.equals("Credit", ignoreCase = true)
    val splitActive = splitOn && !credit
    fun payment() = when {
        credit -> TablePayment("Credit", tendered = amt(paidNow), creditPhone = creditCust?.phone)
        splitActive -> TablePayment(
            mode, splitCash = amt(split.cash), splitOnline = amt(split.online), splitCard = amt(split.card)
        )
        else -> TablePayment(mode, tendered = amt(tendered))
    }
    val canConfirm = !busy && (!splitActive || split.balances()) && (!credit || creditCust != null)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Heading: the table and who it is billed to.
            val tableLabel = d.optString("table") + d.optString("section").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            Text("Table: $tableLabel", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                listOfNotNull(
                    "Customer: " + (tableCustomer?.let { it.name.ifBlank { it.phone } }
                        ?: d.optString("customerPhone").ifBlank { "Walk-in" }),
                    d.optString("billNumber").takeIf { it.isNotBlank() }?.let { "Bill $it" }
                ).joinToString("   ·   "),
                color = Muted, fontSize = 13.sp
            )

            // ---- The order --------------------------------------------------------
            SectionTitle("Order")
            WhiteCard {
                Column {
                    Row {
                        Text("ITEM", Modifier.weight(1f), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("QTY", Modifier.width(44.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("RATE", Modifier.width(70.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("AMOUNT", Modifier.width(80.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    d.optJSONArray("items")?.objects().orEmpty().forEach {
                        Row(Modifier.padding(top = 6.dp)) {
                            Text(it.optString("name"), Modifier.weight(1f), fontSize = 13.sp)
                            Text(qty(it.optDouble("qty")), Modifier.width(44.dp), fontSize = 13.sp)
                            Text(plain(it.optDouble("rate")), Modifier.width(70.dp), fontSize = 13.sp)
                            Text(plain(it.optDouble("amount")), Modifier.width(80.dp), fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    SumRow("Subtotal", d.optDouble("subtotal"))
                    if (d.optDouble("discount") > 0.0) SumRow("Discount", -d.optDouble("discount"))
                    SumRow("Service Charge", d.optDouble("service"))
                    SumRow("CGST", d.optDouble("cgst"))
                    SumRow("SGST", d.optDouble("sgst"))
                    if (d.optDouble("igst") > 0.0) SumRow("IGST", d.optDouble("igst"))
                    if (d.optDouble("vat") > 0.0) SumRow("VAT", d.optDouble("vat"))
                    d.optJSONArray("charges")?.objects().orEmpty().forEach { SumRow(it.optString("label"), it.optDouble("amount")) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total Amount", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(rupee(total), color = Teal, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }
            }

            // ---- Payment ----------------------------------------------------------
            SectionTitle("Payment Method")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { m ->
                    val on = m == mode
                    if (on) Button(
                        onClick = {}, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) { Text(m, fontSize = 13.sp, maxLines = 1) }
                    else OutlinedButton(
                        onClick = { mode = m; error = null }, modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) { Text(m, fontSize = 13.sp, color = Teal, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (credit) {
                CreditPanel(
                    total = total, customer = creditCust, paidNow = paidNow,
                    onPaidNow = { paidNow = it }, onPick = { pickCustomer = true }
                )
            } else {
                // Part Payment - the grocery and dine-in tills' own panel.
                WhiteCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Part Payment", fontWeight = FontWeight.SemiBold)
                                Text("Split the bill across cash, UPI and card", color = Muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = splitOn,
                                onCheckedChange = { splitOn = it; if (!it) split.reset() },
                                colors = SwitchDefaults.colors(checkedTrackColor = Teal)
                            )
                        }
                        if (splitOn) SplitPanel(split)
                    }
                }
                if (!splitOn) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tendered, onValueChange = { tendered = it }, singleLine = true,
                        label = { Text("Amount Tendered") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val t = amtOrNull(tendered)
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("Change Due", Modifier.weight(1f), color = Muted)
                        Text(
                            if (t != null && t >= total) rupee(t - total) else "—",
                            color = Teal, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Scan to pay: Online for the whole bill, or the UPI part of a split.
            val upiAmount = when {
                credit -> null
                splitActive -> amt(split.online).takeIf { it > 0.0 }
                mode.equals("Online", ignoreCase = true) -> total
                else -> null
            }
            if (upiAmount != null && upiId != null) UpiQrCard(upiId, upiPayee, upiAmount)

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Bad, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---- Actions, as the till lays them out -------------------------------------
        Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Back", color = Teal)
                }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val bytes = api.tablePreview(orderId, payment())
                                preview = withContext(Dispatchers.Default) {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }
                                if (preview == null) error = "Could not show the receipt"
                            } catch (e: PosException) {
                                if (e.unauthorized) onUnauthorized() else error = e.message
                            } catch (e: Exception) {
                                error = "Could not get the receipt: ${e.message}"
                            }
                            busy = false
                        }
                    }
                ) { Text("Receipt Preview", color = Teal) }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val r = api.settleTable(orderId, total, payment())
                            val bill = r.optString("billNumber")
                            Toast.makeText(
                                context,
                                "Bill No: ${bill.ifBlank { "-" }} — payment complete. Table ${d.optString("table")} is free.",
                                Toast.LENGTH_LONG
                            ).show()
                            onSettled()
                        } catch (e: PosException) {
                            if (e.unauthorized) { onUnauthorized(); return@launch }
                            error = e.message
                        } catch (e: Exception) {
                            error = "Could not settle: ${e.message}"
                        }
                        busy = false
                    }
                },
                enabled = canConfirm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Confirm Payment  ( ${rupee(total)} )", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (pickCustomer) {
        CustomerPicker(customers, total, onPick = { creditCust = it; pickCustomer = false }, onDismiss = { pickCustomer = false })
    }
    preview?.let { img ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Receipt Preview") },
            text = {
                Box(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).background(Color.White)) {
                    Image(img, contentDescription = "Receipt", modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun SumRow(label: String, value: Double) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = Muted, fontSize = 13.sp)
        Text(if (value < 0) "- ${rupee(-value)}" else rupee(value), fontSize = 13.sp)
    }
}

// ---- Part Payment -------------------------------------------------------------

/**
 * SplitPayment's arithmetic, as on the till: type the parts you know and ONE other
 * row - the one typed in least recently, the panel's order (Cash, UPI, Card) breaking
 * ties - takes the balance. Only cash can be over the bill; UPI and card over it is a
 * mis-key the sale cannot be completed with.
 */
private class SplitState(val total: Double) {
    var cash by mutableStateOf("")
    var online by mutableStateOf("")
    var card by mutableStateOf("")
    private val history = mutableStateListOf<Int>()   // 0 cash, 1 UPI, 2 card - most recent last

    private fun get(i: Int) = when (i) { 0 -> cash; 1 -> online; else -> card }
    private fun set(i: Int, v: String) { when (i) { 0 -> cash = v; 1 -> online = v; else -> card = v } }

    fun type(i: Int, v: String) {
        set(i, v)
        history.remove(i); history.add(i)
        val latest = history.last()
        val follower = (0..2).filter { it != latest }.minByOrNull { history.indexOf(it) } ?: return
        val others = (0..2).filter { it != follower }.sumOf { amt(get(it)) }
        val rest = paise(total - others)
        set(follower, if (rest > 0.005) editable(rest) else "")
    }

    fun reset() { cash = ""; online = ""; card = ""; history.clear() }

    fun entered() = paise(amt(cash) + amt(online) + amt(card))
    fun remaining() = paise(max(total - entered(), 0.0))
    private fun excess() = paise(max(entered() - total, 0.0))
    fun change() = paise(minOf(excess(), amt(cash)))
    fun overpaid() = paise(max(excess() - amt(cash), 0.0))
    fun balances() = remaining() <= 0.001 && overpaid() <= 0.001

    fun note(): String = when {
        entered() <= 0.001 -> "Enter the amount taken in each mode."
        remaining() > 0.001 -> "${rupee(remaining())} still to collect."
        overpaid() > 0.001 -> "UPI and card are ${rupee(overpaid())} over the bill - only cash can be handed back."
        change() > 0.001 -> "${rupee(change())} to hand back."
        else -> "The split covers the bill."
    }
}

@Composable
private fun SplitPanel(s: SplitState) {
    Column(Modifier.padding(top = 8.dp)) {
        listOf("Cash" to 0, "UPI" to 1, "Card" to 2).forEach { (label, i) ->
            OutlinedTextField(
                value = when (i) { 0 -> s.cash; 1 -> s.online; else -> s.card },
                onValueChange = { s.type(i, it) },
                singleLine = true, label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        SumRow("Bill Total", s.total)
        SumRow("Entered", s.entered())
        SumRow("Remaining", s.remaining())
        if (s.change() > 0.001) SumRow("Change", s.change())
        Text(
            s.note(), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            color = if (s.balances()) Good else Bad
        )
    }
}

// ---- Credit -------------------------------------------------------------------

/**
 * The till's Credit panel: who the sale goes on (with their credit status, limit and
 * balance), the amount handed over now, and the balance due on the account.
 */
@Composable
private fun CreditPanel(
    total: Double, customer: Cust?, paidNow: String, onPaidNow: (String) -> Unit, onPick: () -> Unit
) {
    WhiteCard {
        Column {
            Text("Credit", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (customer == null) {
                Text("No customer on this sale.", color = Muted, fontSize = 13.sp)
            } else {
                Text(customer.name.ifBlank { "Unnamed customer" }, fontWeight = FontWeight.SemiBold)
                Text(customer.phone, color = Muted, fontSize = 13.sp)
                Text(
                    if (customer.creditEnabled) "CREDIT  ·  limit left ${rupee(customer.creditLimit)}  ·  balance ${rupee(customer.balance)}"
                    else "NO CREDIT - turn Credit on for this customer on the POS",
                    color = if (customer.creditEnabled && customer.creditLimit >= total - 0.005) Teal else Bad,
                    fontSize = 12.sp
                )
                if (customer.creditEnabled && customer.creditLimit < total - 0.005) {
                    Text(
                        "This sale comes to ${rupee(total)}, but only ${rupee(customer.creditLimit)} of credit is left.",
                        color = Bad, fontSize = 12.sp
                    )
                }
            }
            TextButton(onClick = onPick) { Text(if (customer == null) "Choose customer" else "Change customer") }
            OutlinedTextField(
                value = paidNow, onValueChange = onPaidNow, singleLine = true,
                label = { Text("Amount paid now") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.padding(top = 6.dp)) {
                Text("Balance Due", Modifier.weight(1f), color = Muted)
                Text(rupee(max(total - amt(paidNow), 0.0)), color = Teal, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CustomerPicker(customers: List<Cust>, total: Double, onPick: (Cust) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q, customers) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) customers else customers.filter { it.name.lowercase().contains(t) || it.phone.contains(t) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bill to customer") },
        text = {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Name or phone") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) Text("No customer matches", color = Muted)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { "Unnamed customer" })
                                Text(c.phone, color = Muted, fontSize = 12.sp)
                            }
                            val ok = c.creditEnabled && c.creditLimit >= total - 0.005
                            Text(
                                if (c.creditEnabled) "CREDIT" else "NO CREDIT",
                                color = if (ok) Teal else Bad, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---- Scan to pay --------------------------------------------------------------

/** The till's scan-to-pay code (CheckoutUpiQr): the UPI ID and the amount, in a QR. */
@Composable
private fun UpiQrCard(vpa: String, payee: String, amount: Double) {
    val uri = remember(vpa, payee, amount) { upiUri(vpa, payee, amount) }
    val img = remember(uri) { qrBitmap(uri, 600) }
    Spacer(Modifier.height(10.dp))
    WhiteCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Scan to pay", fontWeight = FontWeight.SemiBold)
            img?.let { Image(it, contentDescription = "UPI QR", modifier = Modifier.size(200.dp).padding(8.dp)) }
            Text(rupee(amount), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(vpa, color = Muted, fontSize = 12.sp)
        }
    }
}

/** UpiQr.payUri's own URI: payee, name, amount to the paise, INR. */
private fun upiUri(vpa: String, payee: String, amount: Double): String {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    val params = buildList {
        add("pa" to vpa.trim())
        payee.trim().take(50).takeIf { it.isNotEmpty() }?.let { add("pn" to it) }
        add("am" to String.format(Locale.US, "%.2f", paise(amount)))
        add("cu" to "INR")
    }
    return params.joinToString("&", prefix = "upi://pay?") { (k, v) -> k + "=" + enc(v) }
}

private fun qrBitmap(content: String, size: Int): ImageBitmap? = runCatching {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

// ---- Money ----------------------------------------------------------------------

private fun paise(v: Double) = Math.round(v * 100.0) / 100.0
private fun amtOrNull(s: String) = s.replace(",", "").trim().toDoubleOrNull()
private fun amt(s: String) = amtOrNull(s) ?: 0.0
/** Ungrouped, so it reads back as typed - see the till's Amounts.editable. */
private fun editable(v: Double) = String.format(Locale.US, "%.2f", paise(v))
private fun plain(v: Double) = String.format(Locale.US, "%,.2f", v)
private fun rupee(v: Double) = "₹ " + plain(v)
