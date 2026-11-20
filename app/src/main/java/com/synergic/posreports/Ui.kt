package com.synergic.posreports

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Teal = Color(0xFF00897B)
val TealDark = Color(0xFF00695C)
val Amber = Color(0xFFFFC107)
val Muted = Color(0xFF6B7280)
val Good = Color(0xFF2E7D32)
val Bad = Color(0xFFC62828)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White, secondary = Amber,
            background = Color(0xFFF5F7F7), surface = Color.White
        ),
        content = content
    )
}

// ---- Formatting ------------------------------------------------------------

private val inr = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}
private val qtyFmt = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 3 }

fun money(v: Double): String = "₹" + inr.format(v)
fun qty(v: Double): String = qtyFmt.format(v)

private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val pretty = SimpleDateFormat("d MMM yyyy", Locale.UK)

fun isoDate(d: Date): String = iso.format(d)
fun prettyDate(isoDate: String): String = runCatching { pretty.format(iso.parse(isoDate)!!) }.getOrDefault(isoDate)
fun today(): String = isoDate(Date())
fun daysAgo(n: Int): String = isoDate(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time)
fun monthStart(): String = isoDate(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.time)
fun yearStart(): String = isoDate(Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1) }.time)

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

// ---- Building blocks -------------------------------------------------------

@Composable
fun SectionTitle(text: String) {
    Text(
        text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TealDark,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { Box(Modifier.padding(14.dp)) { content() } }
}

/** One headline figure: label, value, and an optional small note under it. */
@Composable
fun KpiCard(label: String, value: String, note: String? = null, noteColor: Color = Muted, modifier: Modifier = Modifier) {
    WhiteCard(modifier) {
        Column {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = noteColor, fontSize = 12.sp)
        }
    }
}

/** A label on the left, a figure on the right. */
@Composable
fun LineRow(label: String, value: String, sub: String? = null, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A plain bar chart: one bar per (label, value), labels under the bars. */
@Composable
fun BarChart(points: List<Pair<String, Double>>, barColor: Color = Teal) {
    if (points.isEmpty()) {
        Text("No sales yet", color = Muted, modifier = Modifier.padding(vertical = 12.dp)); return
    }
    val max = points.maxOf { it.second }.coerceAtLeast(1.0)
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / points.size
            val bar = slot * 0.6f
            points.forEachIndexed { i, (_, v) ->
                val h = (v / max).toFloat() * size.height
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(i * slot + (slot - bar) / 2, size.height - h),
                    size = Size(bar, h.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            points.forEach { (label, _) ->
                Text(label, fontSize = 9.sp, color = Muted, maxLines = 1, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** A horizontal share bar under a label - for payment modes and categories. */
@Composable
fun ShareRow(label: String, value: Double, max: Double, color: Color = Teal) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(money(value), fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp).background(Color(0xFFE0F2F1), RoundedCornerShape(3.dp))) {
            Box(
                Modifier.fillMaxWidth((value / max.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f))
                    .height(6.dp).background(color, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    WhiteCard {
        Column {
            Text(message, color = Bad)
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/**
 * From / To pickers and quick ranges. From can never be after To: each picker is
 * limited by the other, the same rule the POS's own report screens use.
 */
@Composable
fun DateRangeBar(from: String, to: String, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    fun pick(current: String, min: String?, max: String?, done: (String) -> Unit) {
        val cal = Calendar.getInstance().apply { time = iso.parse(current) ?: Date() }
        DatePickerDialog(context, { _, y, m, d ->
            done(isoDate(Calendar.getInstance().apply { set(y, m, d) }.time))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            min?.let { datePicker.minDate = iso.parse(it)!!.time }
            max?.let { datePicker.maxDate = iso.parse(it)!!.time }
        }.show()
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick(from, null, to) { onChange(it, to) } }, modifier = Modifier.weight(1f)) {
                Text("From  ${prettyDate(from)}", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { pick(to, from, today()) { onChange(from, it) } }, modifier = Modifier.weight(1f)) {
                Text("To  ${prettyDate(to)}", fontSize = 13.sp)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ranges = listOf(
                "Today" to (today() to today()),
                "Yesterday" to (daysAgo(1) to daysAgo(1)),
                "Last 7 days" to (daysAgo(6) to today()),
                "Last 30 days" to (daysAgo(29) to today()),
                "This month" to (monthStart() to today()),
                "This year" to (yearStart() to today())
            )
            ranges.forEach { (label, r) ->
                FilterChip(selected = from == r.first && to == r.second, onClick = { onChange(r.first, r.second) }, label = { Text(label) })
            }
        }
    }
}
