package com.synergic.posreports

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Any report the POS offers, drawn from its generic table answer: the POS runs the
 * report through its own report screen and sends the title line, the columns, the
 * rows and the totals - exactly what the till shows - and this lays them out.
 *
 * Above the table: the dates, a time-of-day row for reports asked for by the minute,
 * and the report's own filter and mode dropdowns where it has them. The choices the
 * dropdowns offer come from the POS with each answer.
 */
@Composable
fun AnyReportScreen(api: PosApi, id: String, refresh: Int, onUnauthorized: () -> Unit) {
    var from by rememberSaveable(id) { mutableStateOf(today()) }
    var to by rememberSaveable(id) { mutableStateOf(today()) }
    var fromTime by rememberSaveable(id) { mutableStateOf("00:00") }
    var toTime by rememberSaveable(id) { mutableStateOf("23:59") }
    var filter by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(id) { mutableStateOf<String?>(null) }

    var data by remember(id) { mutableStateOf<ReportData?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var retry by remember(id) { mutableIntStateOf(0) }
    val byMinute = data?.grain == "MINUTE"

    LaunchedEffect(id, from, to, fromTime, toTime, filter, mode, refresh, retry) {
        loading = true
        error = null
        try {
            val f = if (byMinute) "$from $fromTime" else from
            val t = if (byMinute) "$to $toTime" else to
            data = ReportData.parse(api.anyReport(id, f, t, filter, mode))
        } catch (e: PosException) {
            if (e.unauthorized) onUnauthorized() else error = e.message
        } catch (e: Exception) {
            error = "Could not load: ${e.message}"
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            DateRangeBar(from, to) { f, t -> from = f; to = t }
            if (byMinute) TimeRow(fromTime, toTime) { f, t -> fromTime = f; toTime = t }
            val d = data
            if (d != null && (d.filter != null || d.mode != null)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.filter?.let { c -> ChoiceField(c, Modifier.weight(1f)) { filter = it } }
                    d.mode?.let { c -> ChoiceField(c, Modifier.weight(1f)) { mode = it } }
                }
            }
        }
        if (loading && data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f)) {
            val d = data
            when {
                error != null -> Box(Modifier.padding(16.dp)) { ErrorBox(error!!) { retry++ } }
                d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> ReportBody(d)
            }
        }
    }
}

// ---- The answer -------------------------------------------------------------

/** One dropdown the report offers: its label, its options and what it ran with. */
class Choice(val hint: String, val options: List<String>, val value: String, val searchable: Boolean)

/** The POS's answer, parsed once rather than on every recomposition. */
class ReportData(
    val title: String,
    val grain: String,
    val subtitle: String,
    val columns: List<String>,
    val alignEnd: List<Boolean>,
    val rows: List<List<String>>,
    val summary: List<Pair<String, String>>,
    val filter: Choice?,
    val mode: Choice?,
    val emptyTitle: String?,
    val emptyHint: String?
) {
    /** Each column's width, from the widest text in it (the first few hundred rows). */
    val widths: List<Float> = columns.indices.map { c ->
        val chars = (sequenceOf(columns[c]) + rows.asSequence().take(300).map { it.getOrElse(c) { "" } })
            .maxOf { it.length }
        (chars * 7.5f + 20f).coerceIn(56f, 260f)
    }

    companion object {
        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }

        private fun choice(o: JSONObject?): Choice? = o?.let {
            Choice(it.optString("hint"), it.optJSONArray("options").strings(), it.optString("value"), it.optBoolean("searchable"))
        }

        fun parse(o: JSONObject): ReportData {
            val rows = o.optJSONArray("rows")
            val alignEnd = o.optJSONArray("alignEnd")
            return ReportData(
                title = o.optString("title", "Report"),
                grain = o.optString("grain", "DAY"),
                subtitle = o.optString("subtitle"),
                columns = o.optJSONArray("columns").strings(),
                alignEnd = if (alignEnd == null) emptyList() else (0 until alignEnd.length()).map { alignEnd.optBoolean(it) },
                rows = if (rows == null) emptyList() else (0 until rows.length()).map { rows.optJSONArray(it).strings() },
                summary = o.optJSONArray("summary")?.objects().orEmpty().map { it.optString("label") to it.optString("value") },
                filter = choice(o.optJSONObject("filter")),
                mode = choice(o.optJSONObject("mode")),
                emptyTitle = o.optJSONObject("empty")?.optString("title"),
                emptyHint = o.optJSONObject("empty")?.optString("hint")
            )
        }
    }
}

// ---- Drawing it -------------------------------------------------------------

/**
 * Laid out as the till lays out a generated report: the headline, the table in its
 * own card taking the room, and the SUMMARY card pinned under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportBody(d: ReportData) {
    // One sideways scroll shared by the header and every row, so they move together.
    val sideways = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.subtitle, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Dead until there is a report to write - the till's rule.
            DownloadButtons(d, enabled = d.emptyTitle == null)
        }
        if (d.emptyTitle != null) {
            WhiteCard {
                Column {
                    Text(d.emptyTitle, fontWeight = FontWeight.SemiBold)
                    if (!d.emptyHint.isNullOrBlank()) Text(d.emptyHint, color = Muted, fontSize = 13.sp)
                }
            }
            return@Column
        }

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // The columns fill the card where there is room, keeping their
                // proportions; where there is not, they keep their widths and scroll.
                val room = maxWidth.value
                val total = d.widths.sum()
                val widths = if (total in 1f..room) d.widths.map { it * room / total } else d.widths
                if (d.rows.isEmpty()) {
                    Text("Nothing to list in this period", color = Muted, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        stickyHeader { TableRow(d.columns, widths, d.alignEnd, sideways, header = true, shade = 0) }
                        itemsIndexed(d.rows) { i, row -> TableRow(row, widths, d.alignEnd, sideways, header = false, shade = i) }
                    }
                }
            }
        }

        if (d.summary.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SummaryCard(d.summary)
        }
    }
}

/**
 * The till's SUMMARY card. Folded shut by default, showing only the total; a tap on
 * the header unfolds the full breakdown, the total as its last, bold line. Lines
 * that come to zero are left out - all but the total, which always shows.
 */
@Composable
private fun SummaryCard(summary: List<Pair<String, String>>) {
    var open by remember(summary) { mutableStateOf(false) }
    val (totalLabel, totalValue) = summary.last()
    val detail = summary.dropLast(1).filterNot { isZero(it.second) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SUMMARY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // The header's total only while folded - open, it is the last line below.
                if (!open) {
                    Text(totalValue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                }
                Chevron(up = !open)
            }
            if (open) {
                Column(Modifier.padding(top = 4.dp)) {
                    detail.forEach { (label, value) -> SummaryLine(label, value, emphasised = false) }
                    SummaryLine(totalLabel, totalValue, emphasised = true)
                }
            }
        }
    }
}

/** A "Label ............ value" line of the summary, in the till's monospace. */
@Composable
private fun SummaryLine(label: String, value: String, emphasised: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, modifier = Modifier.weight(1f), color = Muted,
            fontFamily = FontFamily.Monospace, fontSize = if (emphasised) 13.sp else 11.5.sp,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            value, color = Color(0xFF202124), textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace, fontSize = if (emphasised) 14.sp else 11.5.sp,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * The till's PDF and Excel buttons: the report on screen, written to Downloads by the
 * till's own export code, so the file is the same file the till would save.
 */
@Composable
private fun DownloadButtons(d: ReportData, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun save(asPdf: Boolean) {
        val sheet = ReportExport.Sheet(
            title = d.title, subtitle = d.subtitle, columns = d.columns,
            alignEnd = d.alignEnd, rows = d.rows, summary = d.summary
        )
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    if (asPdf) ReportExport.toPdf(context, sheet) else ReportExport.toExcel(context, sheet)
                }
            }
            Toast.makeText(
                context,
                saved.fold(
                    onSuccess = { "Saved to $it" },
                    onFailure = { "Could not save the ${if (asPdf) "PDF" else "spreadsheet"}" }
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    FileButton(R.drawable.ic_file_pdf, "Download PDF", enabled) { save(asPdf = true) }
    Spacer(Modifier.width(8.dp))
    FileButton(R.drawable.ic_file_excel, "Download Excel", enabled) { save(asPdf = false) }
}

/** A 44dp outlined square holding a file icon, in its own red or green. */
@Composable
private fun FileButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, Teal)
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
    }
}

/** The fold's chevron: ^ while folded (as the till's ic_expand_less), v while open. */
@Composable
private fun Chevron(up: Boolean) {
    Canvas(Modifier.size(28.dp).padding(8.dp)) {
        val w = size.width
        val h = size.height
        val (tipY, armY) = if (up) h * 0.25f to h * 0.75f else h * 0.75f to h * 0.25f
        val stroke = 2.dp.toPx()
        drawLine(Muted, Offset(0f, armY), Offset(w / 2, tipY), stroke, StrokeCap.Round)
        drawLine(Muted, Offset(w / 2, tipY), Offset(w, armY), stroke, StrokeCap.Round)
    }
}

private fun isZero(value: String): Boolean =
    value.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()?.let { abs(it) < 0.005 } ?: false

@Composable
private fun TableRow(
    cells: List<String>, widths: List<Float>, alignEnd: List<Boolean>,
    sideways: ScrollState, header: Boolean, shade: Int
) {
    val bg = when {
        header -> Color(0xFFECEFF1)
        shade % 2 == 1 -> Color.White
        else -> Color(0xFFF7F8FA)
    }
    Row(Modifier.fillMaxWidth().background(bg).horizontalScroll(sideways).padding(vertical = 8.dp)) {
        widths.forEachIndexed { i, w ->
            Text(
                cells.getOrElse(i) { "" },
                modifier = Modifier.width(Dp(w)).padding(horizontal = 6.dp),
                fontSize = 12.sp,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                color = if (header) Muted else Color(0xFF202124),
                textAlign = if (alignEnd.getOrElse(i) { false }) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---- Controls ---------------------------------------------------------------

/**
 * One of the report's dropdowns: a button reading "Waiter: All" that opens the list,
 * with a search box where the list is long (customers, items).
 */
@Composable
private fun ChoiceField(choice: Choice, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text(
            "${choice.hint}: ${choice.value.ifBlank { "Select" }}",
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    if (!open) return
    var query by remember { mutableStateOf("") }
    val shown = remember(query, choice.options) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        choice.options.filter { o -> words.all { o.lowercase().contains(it) } }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(choice.hint) },
        text = {
            Column {
                if (choice.searchable || choice.options.size > 12) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (shown.isEmpty()) Text("Nothing matches", color = Muted)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(shown) { _, option ->
                        Text(
                            option,
                            fontWeight = if (option == choice.value) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == choice.value) TealDark else Color.Unspecified,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { open = false; onPick(option) }
                                .padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
    )
}

/** From / To times of day, for a report asked for by the minute. */
@Composable
private fun TimeRow(from: String, to: String, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    fun pick(current: String, done: (String) -> Unit) {
        val (h, m) = current.split(":").map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
        TimePickerDialog(context, { _, hh, mm -> done("%02d:%02d".format(hh, mm)) }, h, m, true).show()
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pick(from) { onChange(it, to) } }, modifier = Modifier.weight(1f)) {
            Text("From time  $from", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { pick(to) { onChange(from, it) } }, modifier = Modifier.weight(1f)) {
            Text("To time  $to", fontSize = 13.sp)
        }
    }
}
