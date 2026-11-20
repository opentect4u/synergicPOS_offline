package com.synergic.posreports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Loads with [load] whenever [keys] or [refresh] change; shows a spinner, the error, or [content]. */
@Composable
fun Loading(
    vararg keys: Any,
    refresh: Int,
    onUnauthorized: () -> Unit,
    load: suspend () -> JSONObject,
    content: @Composable (JSONObject) -> Unit
) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(*keys, refresh, retry) {
        error = null
        try {
            data = load()
        } catch (e: PosException) {
            if (e.unauthorized) onUnauthorized() else error = e.message
        } catch (e: Exception) {
            error = "Could not load: ${e.message}"
        }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> Box(Modifier.padding(16.dp)) { ErrorBox(error!!) { retry++ } }
            data == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> content(data!!)
        }
    }
}

// ---- Dashboard ---------------------------------------------------------------

@Composable
fun DashboardScreen(api: PosApi, refresh: Int, onUnauthorized: () -> Unit) {
    Loading(refresh = refresh, onUnauthorized = onUnauthorized, load = { api.dashboard() }) { d ->
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Today · ${d.optString("date")}", color = Muted, modifier = Modifier.padding(top = 12.dp))
                val change = if (d.isNull("salesChange")) null else d.optDouble("salesChange")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiCard(
                        "Sales", money(d.optDouble("salesToday")),
                        change?.let { "%+.0f%% vs yesterday".format(it) },
                        if ((change ?: 0.0) >= 0) Good else Bad, Modifier.weight(1f)
                    )
                    KpiCard("Bills", d.optInt("bills").toString(), "Avg ${money(d.optDouble("avgBill"))}", modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiCard("Collected", money(d.optDouble("collected")), "%.0f%% of sales".format(d.optDouble("collectedPct")), modifier = Modifier.weight(1f))
                    KpiCard("Pending", money(d.optDouble("pending")), modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiCard("Customers", d.optInt("customers").toString(), "${d.optInt("repeat")} repeat", modifier = Modifier.weight(1f))
                    KpiCard("New members", d.optInt("newMembers").toString(), modifier = Modifier.weight(1f))
                }

                d.optJSONObject("restaurant")?.let { r ->
                    SectionTitle("Restaurant")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KpiCard("Tables in use", "${r.optInt("occupied")} / ${r.optInt("tables")}", modifier = Modifier.weight(1f))
                        KpiCard("Open KOTs", r.optInt("openKots").toString(), modifier = Modifier.weight(1f))
                    }
                }

                SectionTitle("Sales by hour")
                WhiteCard { BarChart(d.optJSONArray("hourly")?.objects().orEmpty().map { it.optString("label") to it.optDouble("value") }) }

                SectionTitle("Last 7 days")
                WhiteCard { BarChart(d.optJSONArray("daily")?.objects().orEmpty().map { it.optString("label") to it.optDouble("value") }, Amber) }

                val payments = d.optJSONArray("payments")?.objects().orEmpty()
                SectionTitle("Payments today")
                WhiteCard {
                    Column {
                        if (payments.isEmpty()) Text("No payments yet", color = Muted)
                        val max = payments.maxOfOrNull { it.optDouble("value") } ?: 1.0
                        payments.forEach { ShareRow(it.optString("label"), it.optDouble("value"), max) }
                    }
                }

                val categories = d.optJSONArray("categories")?.objects().orEmpty()
                SectionTitle("Top categories today")
                WhiteCard {
                    Column {
                        if (categories.isEmpty()) Text("No sales yet", color = Muted)
                        val max = categories.maxOfOrNull { it.optDouble("value") } ?: 1.0
                        categories.forEach { ShareRow(it.optString("label"), it.optDouble("value"), max, Amber) }
                    }
                }

                val fast = d.optJSONObject("movement")?.optJSONArray("fast")?.objects().orEmpty()
                if (fast.isNotEmpty()) {
                    SectionTitle("Best sellers (last ${d.optJSONObject("movement")?.optInt("days")} days)")
                    WhiteCard {
                        Column { fast.forEach { LineRow(it.optString("label"), "${qty(it.optDouble("value"))} sold") } }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---- Reports -----------------------------------------------------------------

enum class ReportKind(val path: String, val title: String) {
    BILL("bill-wise", "Bill"),
    ITEM("item-wise", "Item"),
    PAYMENT("payment-wise", "Payment"),
    CATEGORY("category-wise", "Category")
}

@Composable
fun ReportScreen(api: PosApi, kind: ReportKind, refresh: Int, onUnauthorized: () -> Unit) {
    var from by rememberSaveable(kind) { mutableStateOf(today()) }
    var to by rememberSaveable(kind) { mutableStateOf(today()) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            DateRangeBar(from, to) { f, t -> from = f; to = t }
        }
        Loading(kind, from, to, refresh = refresh, onUnauthorized = onUnauthorized, load = { api.report(kind.path, from, to) }) { r ->
            val lines = r.optJSONArray("lines")?.objects().orEmpty()
            val totals = r.optJSONObject("totals") ?: JSONObject()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item { Totals(kind, totals) }
                item { SectionTitle(if (lines.isEmpty()) "Nothing sold in this period" else "${lines.size} ${rowsName(kind)}") }
                items(lines) { line ->
                    ReportLine(kind, line)
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun rowsName(kind: ReportKind) = when (kind) {
    ReportKind.BILL -> "bills"
    ReportKind.ITEM -> "items"
    ReportKind.PAYMENT -> "payment modes"
    ReportKind.CATEGORY -> "categories"
}

@Composable
private fun Totals(kind: ReportKind, t: JSONObject) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (kind) {
                ReportKind.BILL -> {
                    KpiCard("Total", money(t.optDouble("amount")), modifier = Modifier.weight(1f))
                    KpiCard("Bills", t.optInt("billCount").toString(), modifier = Modifier.weight(1f))
                }
                ReportKind.ITEM -> {
                    KpiCard("Net amount", money(t.optDouble("netAmount")), modifier = Modifier.weight(1f))
                    KpiCard("Quantity", qty(t.optDouble("quantity")), "${t.optInt("itemCount")} items", modifier = Modifier.weight(1f))
                }
                ReportKind.PAYMENT -> {
                    KpiCard("Collected", money(t.optDouble("collected")), modifier = Modifier.weight(1f))
                    KpiCard("On credit", money(t.optDouble("credit")), modifier = Modifier.weight(1f))
                }
                ReportKind.CATEGORY -> {
                    KpiCard("Total", money(t.optDouble("amount")), modifier = Modifier.weight(1f))
                    KpiCard("Quantity", qty(t.optDouble("quantity")), "${t.optInt("categoryCount")} categories", modifier = Modifier.weight(1f))
                }
            }
        }
        // The tax and charge breakdown, only where the period has any.
        if (kind == ReportKind.BILL || kind == ReportKind.ITEM) {
            val parts = listOf(
                "Discount" to t.optDouble("discount"), "CGST" to t.optDouble("cgst"), "SGST" to t.optDouble("sgst"),
                "IGST" to t.optDouble("igst"), "VAT" to t.optDouble("vat"), "Service charge" to t.optDouble("serviceCharge"),
                "Parcel charge" to t.optDouble("parcelCharge"), "Other charges" to t.optDouble("otherCharges"),
                "Round off" to t.optDouble("roundOff")
            ).filter { !it.second.isNaN() && it.second != 0.0 }
            if (parts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                WhiteCard { Column { parts.forEach { (l, v) -> LineRow(l, money(v)) } } }
            }
        }
        if (kind == ReportKind.PAYMENT) {
            Spacer(Modifier.height(10.dp))
            WhiteCard { LineRow("Total billed", money(t.optDouble("paid")), "${t.optInt("bills")} payments", bold = true) }
        }
    }
}

@Composable
private fun ReportLine(kind: ReportKind, l: JSONObject) {
    when (kind) {
        ReportKind.BILL -> LineRow(
            "Bill ${l.optString("billNumber")}", money(l.optDouble("netAmount")),
            "${prettyDate(l.optString("date"))} · ${l.optString("payMode")}"
        )
        ReportKind.ITEM -> LineRow(l.optString("name"), money(l.optDouble("amount")), "Qty ${qty(l.optDouble("quantity"))}")
        ReportKind.PAYMENT -> LineRow(l.optString("mode"), money(l.optDouble("paidAmount")), "${l.optInt("billCount")} bills")
        ReportKind.CATEGORY -> LineRow(l.optString("category"), money(l.optDouble("amount")), "Qty ${qty(l.optDouble("quantity"))}")
    }
}

@Composable
fun SmallNote(text: String) = Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Normal)
