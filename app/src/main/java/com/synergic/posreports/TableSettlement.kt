package com.synergic.posreports

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** One dine-in table billed on the till and waiting to be paid. */
private class BilledTable(
    val orderId: Long, val table: String, val section: String, val amount: Double,
    val time: String, val items: Int, val billNumber: String
)

/**
 * Table Settlement - offered in the menu only while the POS runs in Restaurant mode.
 *
 * Lists the dine-in tables whose bill has been printed and are waiting for payment,
 * grouped by section. Confirm Payment opens the till's own checkout for that table
 * ([TableCheckoutScreen]); once paid, the bill is saved and the table freed.
 */
@Composable
fun TableSettlementScreen(
    api: PosApi, refresh: Int,
    /** Bumped for every live table event from the POS - see TableEventsClient. */
    live: Int,
    /** The latest of those events. */
    lastEvent: JSONObject?,
    onUnauthorized: () -> Unit
) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    // The table whose checkout is open, by its running order id.
    var checkout by rememberSaveable { mutableStateOf<Long?>(null) }

    // That table paid, or its order cleared, somewhere else - on the POS or another
    // phone - while its checkout is open here: there is nothing left to pay.
    LaunchedEffect(lastEvent) {
        val e = lastEvent ?: return@LaunchedEffect
        val gone = e.optString("type") == "settled" || e.optString("type") == "cleared"
        if (gone && checkout == e.optLong("orderId")) {
            checkout = null
            Toast.makeText(
                context,
                "Table ${e.optString("table")} was " +
                    (if (e.optString("type") == "settled") "paid" else "cleared") + " elsewhere",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    checkout?.let { id ->
        BackHandler { checkout = null }
        TableCheckoutScreen(
            api, id,
            onBack = { checkout = null },
            onSettled = { checkout = null; reload++ },
            onUnauthorized = onUnauthorized
        )
        return
    }

    // Reloaded on every live event, so a table billed on the POS appears and one paid
    // anywhere disappears without a Refresh.
    Loading(reload, live, refresh = refresh, onUnauthorized = onUnauthorized, load = { api.billedTables() }) { d ->
        val tables = d.optJSONArray("tables")?.objects().orEmpty().map { it.toBilledTable() }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "Dine-in tables whose bill is printed, waiting for payment.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (tables.isEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    WhiteCard {
                        Column {
                            Text("No tables waiting for payment", fontWeight = FontWeight.SemiBold)
                            Text("A table shows here once its bill is printed on the POS.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            // By section: a waiter looks for a table in the room they are standing in.
            tables.groupBy { it.section.ifBlank { "Tables" } }.forEach { (section, inSection) ->
                item { SectionTitle(section) }
                items(inSection, key = { it.orderId }) { t ->
                    TableCard(t) { checkout = t.orderId }
                    Spacer(Modifier.height(10.dp))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun JSONObject.toBilledTable() = BilledTable(
    orderId = optLong("orderId"), table = optString("table"), section = optString("section"),
    amount = optDouble("amount"), time = optString("time"), items = optInt("items"),
    billNumber = optString("billNumber")
)

@Composable
private fun TableCard(t: BilledTable, onSettle: () -> Unit) {
    WhiteCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Table ${t.table}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    val meta = listOfNotNull(
                        t.billNumber.takeIf { it.isNotBlank() }?.let { "Bill $it" },
                        "${t.items} item${if (t.items == 1) "" else "s"}",
                        t.time.takeIf { it.isNotBlank() }
                    ).joinToString("  ·  ")
                    Text(meta, color = Muted, fontSize = 12.sp)
                }
                Text(money(t.amount), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSettle,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("Confirm Payment") }
        }
    }
}
