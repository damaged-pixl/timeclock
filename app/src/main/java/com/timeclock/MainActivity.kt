package com.timeclock

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
        for (s in sessions(all)) {
            if (s.outTs != null) {
                sb.append("duration_minutes,")
                    .append(String.format(Locale.UK, "%.1f", (s.outTs - s.inTs) / 60000.0)).append('\n')
            }
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
                    totalMs = totalInRange(all, day, day + 86_400_000, nowTs)
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

        val clockedIn = isClockedIn(punches)
        val dayStart = floorDay(now)
        val dayEnd = dayStart + 86_400_000 // exclusive: midnight tonight
        val today = totalInRange(punches, dayStart, dayEnd, now)
        val week = totalInRange(punches, floorWeek(now), dayEnd, now)
        val month = totalInRange(punches, floorMonth(now), dayEnd, now)
        val allTime = totalInRange(punches, Long.MIN_VALUE, Long.MAX_VALUE, now)
        val sessionMs = if (clockedIn) now - punches.last().ts else 0L
        val history = buildHistory(punches, now)
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            LazyColumn(
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
                        StatCard("THIS MONTH", fmtDuration(month), money(month), Modifier.weight(1f))
                        StatCard("ALL TIME", fmtDuration(allTime), money(allTime), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(32.dp))

                    Text(
                        "History",
                        color = Muted, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (history.isEmpty()) {
                    item {
                        Text("No punches yet", color = Muted, modifier = Modifier.padding(8.dp))
                    }
                } else for (day in history) {
                    item(key = "day-${day.dayStart}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 2.dp),
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
                        HistRowView(row, now, onLongPress = { pendingDelete = row.punch })
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
                        TextButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) {
                            Text("Hourly rate", color = Accent)
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
    }

    @Composable
    private fun StatCard(label: String, value: String, pay: String, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .background(Card, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(label, color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
            Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (ratePence > 0) {
                Text(pay, color = Accent, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    @androidx.compose.foundation.ExperimentalFoundationApi
    @Composable
    private fun HistRowView(row: HistRow, nowTs: Long, onLongPress: () -> Unit) {
        val dot = when (row) {
            is HistRow.InRow -> InGreen
            is HistRow.OutRow -> OutRed
            is HistRow.Stray -> Muted
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
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
