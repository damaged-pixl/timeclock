package com.timeclock

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private val Bg = Color(0xFF10131F)
private val Card = Color(0xFF1C2333)
private val Muted = Color(0xFF8A90A6)
private val Accent = Color(0xFF4C8DFF)
private val InGreen = Color(0xFF2E9E5B)
private val OutRed = Color(0xFFD05454)

/** One display row in the history list. Sessions dictate pairing so the view
 *  always matches the totals; punches no session claims show as strays. */
private sealed class HistRow {
    abstract val punch: Punch
    data class InRow(override val punch: Punch, val open: Boolean) : HistRow()
    data class OutRow(override val punch: Punch, val sessionMs: Long) : HistRow()
    data class Stray(override val punch: Punch) : HistRow()
}

private data class DayHist(val dayStart: Long, val rows: List<HistRow>, val totalMs: Long)

class MainActivity : ComponentActivity() {

    private lateinit var db: TimeDb
    private var punches by mutableStateOf<List<Punch>>(emptyList())
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private var showAddDialog by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var pendingDelete by mutableStateOf<Punch?>(null)
    private var confirmReset by mutableStateOf(false)
    private var ratePence by mutableIntStateOf(0)

    /** Non-null while the month report is open; holds that month's first instant. */
    private var reportMonth by mutableStateOf<Long?>(null)
    private var editingPunch by mutableStateOf<Punch?>(null)

    /** CSV restore: reads punches back in, skipping ones already recorded. */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCsv(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = TimeDb(this)
        ratePence = getSharedPreferences("settings", MODE_PRIVATE).getInt("rate_pence", 0)
        seedIfEmpty()
        reload()
        setContent { App() }
    }

    private fun reload() {
        punches = db.allPunches()
    }

    /**
     * One-time seed of the real punches, recovered 2026-09-02 after the old build
     * duplicated "in" punches and lost clock-outs. Only runs on a completely empty db.
     */
    private fun seedIfEmpty() {
        if (db.allPunches().isNotEmpty()) return

        fun at(dayOffset: Int, h: Int, m: Int): Long = Calendar.getInstance().run {
            // Seeded on 2026-09-02: dayOffset 0 = 2 Sept, -1 = 1 Sept.
            set(2026, Calendar.SEPTEMBER, 2 + dayOffset, h, m, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        // 1 Sept — only the 1:52 total was known, so times are placeholders 09:00–10:52.
        db.addPunch("in", at(-1, 9, 0))
        db.addPunch("out", at(-1, 10, 52))
        // 2 Sept — actual punches reported by Bradley.
        db.addPunch("in", at(0, 9, 7))
        db.addPunch("out", at(0, 9, 24))
        db.addPunch("in", at(0, 15, 12))
        db.addPunch("out", at(0, 16, 11))
        db.addPunch("in", at(0, 17, 4))
        db.addPunch("out", at(0, 17, 16))
    }

    private fun toggle() {
        val type = if (isClockedIn(punches)) "out" else "in"
        db.addPunch(type, System.currentTimeMillis())
        reload()
    }

    private fun undoLast() {
        val last = punches.lastOrNull() ?: return
        db.deletePunch(last.id)
        reload()
    }

    private fun addManual(dayTs: Long, inH: Int, inM: Int, outH: Int?, outM: Int?) {
        fun withTime(h: Int, m: Int) = Calendar.getInstance().run {
            timeInMillis = floorDay(dayTs)
            add(Calendar.HOUR_OF_DAY, h)
            add(Calendar.MINUTE, m)
            timeInMillis
        }
        db.addPunch("in", withTime(inH, inM))
        if (outH != null && outM != null) db.addPunch("out", withTime(outH, outM))
        reload()
    }

    private fun saveRate(pence: Int) {
        ratePence = pence
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("rate_pence", pence).apply()
    }

    private fun money(ms: Long): String =
        String.format(Locale.UK, "£%.2f", ms / 3_600_000.0 * ratePence / 100.0)

    private fun export() {
        val all = punches
        val sb = StringBuilder("type,datetime\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
        for (p in all) sb.append(p.type).append(',').append(fmt.format(Date(p.ts))).append('\n')
        // Session/day/month rows are for reading in a spreadsheet; restore ignores them.
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val monthFmt = SimpleDateFormat("yyyy-MM", Locale.UK)
        for (s in sessions(all)) {
            if (s.outTs != null) {
                sb.append("session_minutes,")
                    .append(fmt.format(Date(s.inTs))).append(',')
                    .append(String.format(Locale.UK, "%.1f", (s.outTs - s.inTs) / 60000.0)).append('\n')
            }
        }
        val hist = buildHistory(all, now)
        for (d in hist) {
            sb.append("day_minutes,").append(dayFmt.format(Date(d.dayStart))).append(',')
                .append(String.format(Locale.UK, "%.1f", d.totalMs / 60000.0)).append('\n')
        }
        for (m in hist.groupBy { floorMonth(it.dayStart) }.keys) {
            val ms = totalInRange(all, m, shiftMonth(m, 1), now)
            sb.append("month_minutes,").append(monthFmt.format(Date(m))).append(',')
                .append(String.format(Locale.UK, "%.1f", ms / 60000.0)).append('\n')
        }
        val content = sb.toString()
        thread {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "timeclock-export.csv")
            file.writeText(content)
            runOnUiThread {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Send time clock export"))
            }
        }
    }

    /**
     * Restore punches from an exported CSV. Only "in"/"out" rows with a valid
     * timestamp are read, so summary rows (and the header) are ignored. Anything
     * already in the db is skipped, so importing the same file twice is harmless.
     */
    private fun importCsv(uri: Uri) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).apply { isLenient = false }
        thread {
            var added = 0
            var skipped = 0
            var failed = false
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("could not open file")
                for (raw in text.lineSequence()) {
                    val parts = raw.trim().split(',')
                    if (parts.size < 2) continue
                    val type = parts[0]
                    if (type != "in" && type != "out") continue
                    val ts = try { fmt.parse(parts[1])?.time } catch (e: Exception) { null } ?: continue
                    if (db.hasPunch(ts, type)) skipped++ else { db.addPunch(type, ts); added++ }
                }
            } catch (e: Exception) {
                failed = true
            }
            runOnUiThread {
                reload()
                Toast.makeText(
                    this,
                    if (failed) "Restore failed — is that a timeclock CSV?"
                    else "Restored $added punches, skipped $skipped duplicates",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveEdit(punch: Punch, ts: Long) {
        db.updatePunchTime(punch.id, ts)
        editingPunch = null
        reload()
    }

    /** Group punches into per-day history rows, newest day first. Pairing comes
     *  from sessions() so displayed durations can never disagree with the totals. */
    private fun buildHistory(all: List<Punch>, nowTs: Long): List<DayHist> {
        val byTs = all.associateBy { it.ts }
        val covered = HashSet<Long>()
        val rows = ArrayList<Pair<Long, HistRow>>()
        for (s in sessions(all)) {
            val inPunch = byTs[s.inTs] ?: continue
            covered.add(s.inTs)
            rows += s.inTs to HistRow.InRow(inPunch, s.outTs == null)
            if (s.outTs != null) {
                val outPunch = byTs[s.outTs] ?: continue
                covered.add(s.outTs)
                rows += s.outTs to HistRow.OutRow(outPunch, s.outTs - s.inTs)
            }
        }
        for (p in all) {
            if (p.ts !in covered) rows += p.ts to HistRow.Stray(p)
        }
        return rows.groupBy { floorDay(it.first) }
            .map { (day, dayRows) ->
                DayHist(
                    dayStart = day,
                    rows = dayRows.sortedBy { it.first }.map { it.second },
                    totalMs = totalInRange(all, day, nextDayStart(day), nowTs)
                )
            }
            .sortedByDescending { it.dayStart }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun App() {
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }

        // System back closes the month report instead of leaving the app.
        BackHandler(enabled = reportMonth != null) { reportMonth = null }

        val clockedIn = isClockedIn(punches)
        val dayStart = floorDay(now)
        val dayEnd = nextDayStart(dayStart) // exclusive: midnight tonight
        val today = totalInRange(punches, dayStart, dayEnd, now)
        val week = totalInRange(punches, floorWeek(now), dayEnd, now)
        val month = totalInRange(punches, floorMonth(now), dayEnd, now)
        val lastMonthStart = shiftMonth(now, -1)
        val lastMonth = totalInRange(punches, lastMonthStart, floorMonth(now), now)
        val allTime = totalInRange(punches, Long.MIN_VALUE, Long.MAX_VALUE, now)
        val sessionMs = if (clockedIn) now - punches.last().ts else 0L
        val history = buildHistory(punches, now)
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            val report = reportMonth
            if (report != null) {
                MonthReport(
                    month = report,
                    onMonthChange = { reportMonth = it },
                    onBack = { reportMonth = null }
                )
            } else LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date(now)),
                        fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Text(
                        if (clockedIn) "CLOCKED IN  ${fmtClock(punches.last().ts)}" else "CLOCKED OUT",
                        color = if (clockedIn) InGreen else Muted,
                        fontSize = 15.sp, letterSpacing = 3.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    if (clockedIn) {
                        Text(
                            "this session  ${fmtHms(sessionMs)}",
                            color = InGreen, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Text(
                        SimpleDateFormat("EEE d MMM yyyy", Locale.UK).format(Date(now)),
                        color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(32.dp))

                    Button(
                        onClick = { toggle() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (clockedIn) OutRed else InGreen
                        ),
                        modifier = Modifier.size(220.dp)
                    ) {
                        Text(
                            if (clockedIn) "CLOCK OUT" else "CLOCK IN",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(36.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("TODAY", fmtDuration(today), money(today), Modifier.weight(1f))
                        StatCard("THIS WEEK", fmtDuration(week), money(week), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(
                            "THIS MONTH", fmtDuration(month), money(month), Modifier.weight(1f),
                            onClick = { reportMonth = floorMonth(now) }
                        )
                        StatCard(
                            "LAST MONTH ›", fmtDuration(lastMonth), money(lastMonth), Modifier.weight(1f),
                            onClick = { reportMonth = lastMonthStart }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    StatCard(
                        "ALL TIME ›", fmtDuration(allTime), money(allTime),
                        Modifier.fillMaxWidth(),
                        onClick = { reportMonth = floorMonth(now) }
                    )
                    Spacer(Modifier.height(32.dp))

                    Text(
                        "History",
                        color = Muted, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Tap a punch to edit · long-press to delete",
                        color = Muted, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (history.isEmpty()) {
                    item {
                        Text("No punches yet", color = Muted, modifier = Modifier.padding(8.dp))
                    }
                } else for ((monthStart, monthDays) in history.groupBy { floorMonth(it.dayStart) }) {
                    item(key = "month-$monthStart") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                SimpleDateFormat("MMMM yyyy", Locale.UK)
                                    .format(Date(monthStart)).uppercase(Locale.UK),
                                color = Accent, fontSize = 12.sp, letterSpacing = 1.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                fmtDuration(totalInRange(punches, monthStart, shiftMonth(monthStart, 1), now)),
                                color = Muted, fontSize = 12.sp
                            )
                        }
                    }
                    for (day in monthDays) {
                        item(key = "day-${day.dayStart}") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (day.dayStart == dayStart) "Today" else dayFmt.format(Date(day.dayStart)),
                                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.weight(1f))
                                Text(fmtDuration(day.totalMs), color = Muted, fontSize = 16.sp)
                            }
                        }
                        items(day.rows, key = { it.punch.id }) { row ->
                            HistRowView(
                                row, now,
                                onClick = { editingPunch = row.punch },
                                onLongPress = { pendingDelete = row.punch }
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { export() },
                        colors = ButtonDefaults.buttonColors(containerColor = Card, contentColor = Accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Export CSV / Backup") }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showAddDialog = true }, modifier = Modifier.weight(1f)) {
                            Text("Add missed punch", color = Accent)
                        }
                        TextButton(
                            onClick = { undoLast() },
                            enabled = punches.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Undo last", color = if (punches.isEmpty()) Muted else Accent)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { reportMonth = floorMonth(now) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Monthly report", color = Accent)
                        }
                        TextButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) {
                            Text("Hourly rate", color = Accent)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { importLauncher.launch(arrayOf("text/*", "application/csv")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import / restore", color = Accent)
                        }
                        TextButton(onClick = { confirmReset = true }, modifier = Modifier.weight(1f)) {
                            Text("Reset all data", color = OutRed)
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }

        pendingDelete?.let { p ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete punch?") },
                text = {
                    Text("${p.type.uppercase()} at ${fmtClock(p.ts)} on " +
                        SimpleDateFormat("d MMM", Locale.UK).format(Date(p.ts)))
                },
                confirmButton = {
                    TextButton(onClick = { db.deletePunch(p.id); reload(); pendingDelete = null }) {
                        Text("Delete", color = OutRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                }
            )
        }

        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text("Reset all data") },
                text = { Text("Delete all time data? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = { db.clear(); reload(); confirmReset = false }) {
                        Text("Delete", color = OutRed)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
            )
        }

        if (showAddDialog) {
            AddPunchDialog(onDismiss = { showAddDialog = false }, onAdd = ::addManual)
        }

        if (showSettings) {
            RateDialog(onDismiss = { showSettings = false })
        }

        editingPunch?.let { p ->
            EditPunchDialog(
                punch = p,
                onDismiss = { editingPunch = null },
                onSave = { ts -> saveEdit(p, ts) },
                onDelete = { db.deletePunch(p.id); reload(); editingPunch = null }
            )
        }
    }

    @Composable
    private fun StatCard(
        label: String,
        value: String,
        pay: String,
        modifier: Modifier = Modifier,
        onClick: (() -> Unit)? = null
    ) {
        Column(
            modifier = modifier
                .background(Card, RoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(16.dp)
        ) {
            Text(label, color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
            Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (ratePence > 0 && pay.isNotEmpty()) {
                Text(pay, color = Accent, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    @androidx.compose.foundation.ExperimentalFoundationApi
    @Composable
    private fun HistRowView(row: HistRow, nowTs: Long, onClick: () -> Unit, onLongPress: () -> Unit) {
        val dot = when (row) {
            is HistRow.InRow -> InGreen
            is HistRow.OutRow -> OutRed
            is HistRow.Stray -> Muted
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(dot, CircleShape)
            )
            Spacer(Modifier.size(12.dp))
            when (row) {
                is HistRow.InRow -> Text(
                    if (row.open) "In  ${fmtClock(row.punch.ts)}  · running ${fmtHms(nowTs - row.punch.ts)}"
                    else "In  ${fmtClock(row.punch.ts)}",
                    color = if (row.open) InGreen else Color.White, fontSize = 16.sp
                )
                is HistRow.OutRow -> {
                    Text("Out ${fmtClock(row.punch.ts)}", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(fmtDuration(row.sessionMs), color = Muted, fontSize = 15.sp)
                }
                is HistRow.Stray -> Text(
                    "${row.punch.type.uppercase()} ${fmtClock(row.punch.ts)}  · unpaired",
                    color = Muted, fontSize = 16.sp
                )
            }
        }
    }

    /** Month-by-month report: any past month, with day totals and expandable punches. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun MonthReport(month: Long, onMonthChange: (Long) -> Unit, onBack: () -> Unit) {
        val nowTs = now
        val summary = summariseMonth(punches, month, nowTs)
        val canNext = shiftMonth(month, 1) <= floorMonth(nowTs)
        val monthName = SimpleDateFormat("MMMM yyyy", Locale.UK).format(Date(month))
        val monthLast = shiftMonth(month, 1)
        val days = buildHistory(punches, nowTs).filter { it.dayStart >= summary.monthStart && it.dayStart < monthLast }
        val maxDay = summary.dayTotals.maxOfOrNull { it.totalMs } ?: 1L
        var expandedDay by remember(month) { mutableStateOf<Long?>(null) }
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)
        val avg = if (summary.daysWorked > 0) summary.totalMs / summary.daysWorked else 0L

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(Modifier.height(40.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back", color = Accent, fontSize = 16.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("REPORTS", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onMonthChange(shiftMonth(month, -1)) }) {
                        Text("‹", color = Accent, fontSize = 26.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(monthName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onMonthChange(shiftMonth(month, 1)) }, enabled = canNext) {
                        Text("›", color = if (canNext) Accent else Muted, fontSize = 26.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    fmtDuration(summary.totalMs),
                    color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold
                )
                Text("total hours", color = Muted, fontSize = 13.sp, letterSpacing = 2.sp)
                if (ratePence > 0) {
                    Text(
                        money(summary.totalMs), color = Accent, fontSize = 18.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("DAYS WORKED", summary.daysWorked.toString(), "", Modifier.weight(1f))
                    StatCard(
                        "AVG / DAY",
                        if (summary.daysWorked > 0) fmtDuration(avg) else "–",
                        "", Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(28.dp))
                Text("Days", color = Muted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }

            if (days.isEmpty()) {
                item { Text("No punches this month", color = Muted, modifier = Modifier.padding(16.dp)) }
            } else for (day in days) {
                item(key = "mday-${day.dayStart}") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    expandedDay = if (expandedDay == day.dayStart) null else day.dayStart
                                },
                                onLongClick = {}
                            )
                            .padding(vertical = 8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (day.dayStart == floorDay(nowTs)) "Today" else dayFmt.format(Date(day.dayStart)),
                                color = Color.White, fontSize = 16.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                fmtDuration(day.totalMs) + if (expandedDay == day.dayStart) "  ▾" else "  ›",
                                color = if (expandedDay == day.dayStart) Accent else Muted, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).background(Card, CircleShape)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((day.totalMs.toFloat() / maxDay).coerceIn(0.02f, 1f))
                                    .height(6.dp)
                                    .background(Accent, CircleShape)
                            )
                        }
                    }
                }
                if (expandedDay == day.dayStart) {
                    items(day.rows, key = { "m-${it.punch.id}" }) { row ->
                        HistRowView(
                            row, nowTs,
                            onClick = { editingPunch = row.punch },
                            onLongPress = { pendingDelete = row.punch }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Card, contentColor = Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Back to clock") }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    /** Fix a mistyped punch in place instead of deleting and re-adding it. */
    @Composable
    private fun EditPunchDialog(punch: Punch, onDismiss: () -> Unit, onSave: (Long) -> Unit, onDelete: () -> Unit) {
        var ts by remember(punch.id) { mutableLongStateOf(punch.ts) }
        val dateFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.UK)

        fun pickDate() {
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    // set() keeps the current time of day, only the date changes.
                    ts = Calendar.getInstance().apply {
                        timeInMillis = ts
                        set(y, m, d)
                    }.timeInMillis
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        fun pickTime() {
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            TimePickerDialog(
                this,
                { _, h, m ->
                    ts = Calendar.getInstance().apply {
                        timeInMillis = ts
                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
            ).show()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit ${if (punch.isIn) "in" else "out"} punch") },
            text = {
                Column {
                    TextButton(onClick = { pickDate() }) {
                        Text(dateFmt.format(Date(ts)), color = Accent)
                    }
                    TextButton(onClick = { pickTime() }) {
                        Text(fmtClock(ts), color = Accent, fontSize = 22.sp)
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete this punch", color = OutRed)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(ts) }) { Text("Save", color = Accent) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @Composable
    private fun RateDialog(onDismiss: () -> Unit) {
        var text by remember {
            mutableStateOf(if (ratePence > 0) "%d.%02d".format(ratePence / 100, ratePence % 100) else "")
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Hourly rate") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("£ per hour") },
                        singleLine = true
                    )
                    Text(
                        "Set to 0 to hide earnings.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveRate(((text.toDoubleOrNull() ?: 0.0) * 100).roundToInt())
                    onDismiss()
                }) { Text("Save", color = Accent) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @Composable
    private fun AddPunchDialog(onDismiss: () -> Unit, onAdd: (Long, Int, Int, Int?, Int?) -> Unit) {
        var day by remember { mutableLongStateOf(floorDay(System.currentTimeMillis())) }
        var inTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var outTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val dateFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

        fun pickDate() {
            val c = Calendar.getInstance().apply { timeInMillis = day }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    day = Calendar.getInstance().apply {
                        set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        fun pickTime(current: Pair<Int, Int>?, set: (Pair<Int, Int>) -> Unit) {
            TimePickerDialog(
                this,
                { _, h, m -> set(h to m) },
                current?.first ?: 9, current?.second ?: 0, true
            ).show()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add missed punch") },
            text = {
                Column {
                    TextButton(onClick = { pickDate() }) {
                        Text("Day: ${dateFmt.format(Date(day))}", color = Accent)
                    }
                    TextButton(onClick = { pickTime(inTime) { inTime = it } }) {
                        Text("In:  ${inTime?.let { "%02d:%02d".format(it.first, it.second) } ?: "tap to set"}", color = Accent)
                    }
                    TextButton(onClick = { pickTime(outTime) { outTime = it } }) {
                        Text("Out: ${outTime?.let { "%02d:%02d".format(it.first, it.second) } ?: "leave open"}", color = Accent)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = inTime
                    if (i != null) {
                        onAdd(day, i.first, i.second, outTime?.first, outTime?.second)
                        onDismiss()
                    }
                }) { Text("Add", color = Accent) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }
}
