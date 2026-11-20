package com.synergic.posreports

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark status/nav bar icons - the app's background is light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // Table updates from the POS arrive as notifications - Android 13+ asks first.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val store = ConnectionStore(this)
        val api = PosApi(this, store)
        val tableEvents = TableEventsClient(store)
        setContent {
            AppTheme {
                var paired by remember { mutableStateOf(store.load() != null) }
                // Why the app is on the connect screen, when it was put there - the POS
                // user logging out, for one. Shown at the top of that screen.
                var notice by remember { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize().background(Color(0xFFF5F7F7)).safeDrawingPadding()) {
                    if (!paired) {
                        PairScreen(api, store, notice) { notice = null; paired = true }
                    } else {
                        ReportsHome(
                            api = api,
                            tableEvents = tableEvents,
                            posAddress = store.load()?.ip.orEmpty(),
                            onUnpaired = {
                                notice = api.lastRefusal.also { api.lastRefusal = null }
                                paired = false
                            },
                            onLoggedOut = {
                                notice = "The POS user logged out, so this phone was disconnected. " +
                                    "Log in on the POS, then tap Reconnect."
                                paired = false
                            },
                            onDisconnect = { store.clear(); paired = false }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One entry in the side menu: the Dashboard, a report the POS lists ([reportId]), or -
 * for a POS still on an older till app - one of the four reports it always had ([legacy]).
 */
private data class MenuEntry(
    val key: String,
    val title: String,
    val group: String,
    val reportId: String? = null,
    val legacy: ReportKind? = null
)

private val DASHBOARD = MenuEntry("dashboard", "Dashboard", "Overview")

/** Restaurant mode only - a grocery till has no tables to settle. */
private val TABLE_SETTLEMENT = MenuEntry("table-settlement", "Table Settlement", "Restaurant")

/** What an older POS, without the report catalog, can show. */
private val LEGACY_REPORTS = listOf(
    MenuEntry("legacy:BILL", "Bill Wise Report", "Reports", legacy = ReportKind.BILL),
    MenuEntry("legacy:ITEM", "Item Wise Report", "Reports", legacy = ReportKind.ITEM),
    MenuEntry("legacy:PAYMENT", "Payment Wise Report", "Reports", legacy = ReportKind.PAYMENT),
    MenuEntry("legacy:CATEGORY", "Category Wise Report", "Reports", legacy = ReportKind.CATEGORY)
)

/**
 * The paired app: a header with the ☰ menu button, a side menu listing the dashboard
 * and every report the POS's own Reports screen shows, and the chosen screen.
 */
@Composable
fun ReportsHome(
    api: PosApi, tableEvents: TableEventsClient, posAddress: String,
    onUnpaired: () -> Unit, onDisconnect: () -> Unit,
    /** The POS user logged out - told over the event socket. */
    onLoggedOut: () -> Unit = onUnpaired
) {
    var selectedKey by rememberSaveable { mutableStateOf(DASHBOARD.key) }
    var refresh by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Live table changes from the POS, over its event socket, for as long as this
    // screen is up: a table billed, paid or cleared anywhere reloads what shows tables.
    var liveCount by remember { mutableIntStateOf(0) }
    var lastTableEvent by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(Unit) {
        tableEvents.events().collect { e ->
            // The POS user logged out: this phone is disconnected along with them.
            if (e.optString("type") == "logout") {
                onLoggedOut()
                return@collect
            }
            lastTableEvent = e
            liveCount++
            // Billed, paid or cleared on the POS - or paid on another phone - as a
            // notification, the way the POS is notified of a phone's payment.
            PhoneNotifications.show(context, e)
        }
    }
    var askDisconnect by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The reports, as the POS lists them - so the menu matches the till: the same
    // reports in the same order, the restaurant-only ones only on a restaurant till.
    var reports by remember { mutableStateOf<List<MenuEntry>?>(null) }
    var reportsError by remember { mutableStateOf<String?>(null) }
    var reportsTry by remember { mutableIntStateOf(0) }
    // Whether the POS runs in Restaurant mode - read with the reports, so a till
    // switched between Restaurant and Grocery is picked up on the next Refresh.
    var restaurant by remember { mutableStateOf(false) }
    LaunchedEffect(reportsTry, refresh) {
        try {
            val catalog = api.catalog()
            reports = catalog.optJSONArray("reports")?.objects().orEmpty().map {
                MenuEntry("report:${it.optString("id")}", it.optString("title"), "Reports", reportId = it.optString("id"))
            }
            restaurant = catalog.optBoolean("restaurant", false)
            reportsError = null
        } catch (e: PosException) {
            when {
                e.unauthorized -> onUnpaired()
                // An older till: no catalog, and no way to tell its mode either.
                e.notFound -> { reports = LEGACY_REPORTS; restaurant = false; reportsError = null }
                reports == null -> reportsError = e.message
            }
        } catch (e: Exception) {
            if (reports == null) reportsError = "Could not load the reports: ${e.message}"
        }
    }

    // A menu entry that is not on this till's menu (Table Settlement on a grocery
    // till) falls back to the Dashboard rather than staying on screen.
    val menu = listOf(DASHBOARD) + listOfNotNull(TABLE_SETTLEMENT.takeIf { restaurant }) + reports.orEmpty()
    val current = menu.firstOrNull { it.key == selectedKey } ?: DASHBOARD

    // Whether the menu's Reports branch is unfolded. Opens on its own whenever a
    // report is chosen; folds only when the user folds it.
    val onReport = current.reportId != null || current.legacy != null
    var reportsOpen by rememberSaveable { mutableStateOf(onReport) }
    LaunchedEffect(current.key) { if (onReport) reportsOpen = true }

    // Back closes the menu first, rather than leaving the app.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp), drawerContainerColor = Color.White) {
                // Menu header: the app and the POS it reads from.
                Column(Modifier.fillMaxWidth().background(TealDark).padding(20.dp)) {
                    Text("SynergicPOS Reports", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Connected to POS $posAddress", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                }
                // Scrolls: a till can list nearly thirty reports.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    @Composable
                    fun item(entry: MenuEntry) {
                        val isSelected = entry.key == current.key
                        NavigationDrawerItem(
                            label = { Text(entry.title, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                            selected = isSelected,
                            onClick = {
                                selectedKey = entry.key
                                scope.launch { drawer.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFFE0F2F1),
                                selectedTextColor = TealDark
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    MenuGroup(DASHBOARD.group)
                    item(DASHBOARD)

                    if (restaurant) {
                        MenuGroup(TABLE_SETTLEMENT.group)
                        item(TABLE_SETTLEMENT)
                    }

                    // Reports: a branch that folds open and shut. Open while a report
                    // is on screen, so the one being read is always in view.
                    MenuBranch("Reports", reports?.size, reportsOpen) { reportsOpen = !reportsOpen }
                    AnimatedVisibility(visible = reportsOpen && reports != null) {
                        Column { reports.orEmpty().forEach { item(it) } }
                    }
                    if (reports == null && reportsOpen) {
                        if (reportsError == null) {
                            Text("Loading reports…", color = Muted, modifier = Modifier.padding(start = 28.dp, top = 4.dp))
                        } else {
                            Text(reportsError!!, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 28.dp))
                            TextButton(onClick = { reportsError = null; reportsTry++ }, modifier = Modifier.padding(start = 16.dp)) {
                                Text("Try again")
                            }
                        }
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Disconnect", color = Bad) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; askDisconnect = true },
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header: ☰ menu, the screen's name, Refresh.
            Row(
                Modifier.fillMaxWidth().background(TealDark).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MenuButton { scope.launch { drawer.open() } }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(current.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("POS $posAddress", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                TextButton(onClick = { refresh++ }) { Text("Refresh", color = Color.White) }
            }
            Box(Modifier.weight(1f)) {
                val legacy = current.legacy
                val reportId = current.reportId
                when {
                    // Keyed by report, so one report's dates and choices never carry
                    // over into the next one opened.
                    reportId != null -> key(reportId) { AnyReportScreen(api, reportId, refresh, onUnpaired) }
                    legacy != null -> ReportScreen(api, legacy, refresh, onUnpaired)
                    current.key == TABLE_SETTLEMENT.key ->
                        TableSettlementScreen(api, refresh, liveCount, lastTableEvent, onUnpaired)
                    // Today's figures move with every table paid - live too.
                    else -> DashboardScreen(api, refresh + liveCount, onUnpaired)
                }
            }
        }
    }

    if (askDisconnect) {
        AlertDialog(
            onDismissRequest = { askDisconnect = false },
            title = { Text("Disconnect from this POS?") },
            text = { Text("You will need to scan the POS's QR code again to see reports.") },
            confirmButton = { TextButton(onClick = { askDisconnect = false; onDisconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { askDisconnect = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MenuGroup(name: String) {
    Text(
        name.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp)
    )
}

/**
 * A menu heading that folds its entries open and shut: the name, how many entries it
 * holds, and a chevron - v while open, > while shut.
 */
@Composable
private fun MenuBranch(name: String, count: Int?, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp)).clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) Text("  ($count)", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(20.dp).padding(5.dp).rotate(turn)) {
            val stroke = 2.dp.toPx()
            drawLine(Muted, Offset(0f, size.height * 0.3f), Offset(size.width / 2, size.height * 0.75f), stroke, StrokeCap.Round)
            drawLine(Muted, Offset(size.width / 2, size.height * 0.75f), Offset(size.width, size.height * 0.3f), stroke, StrokeCap.Round)
        }
    }
}

/** The ☰ button: three white lines, drawn rather than taken from an icon library. */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.4.dp.toPx()
            listOf(0.2f, 0.5f, 0.8f).forEach { y ->
                drawLine(
                    Color.White, Offset(0f, size.height * y), Offset(size.width, size.height * y),
                    strokeWidth = stroke, cap = StrokeCap.Round
                )
            }
        }
    }
}
