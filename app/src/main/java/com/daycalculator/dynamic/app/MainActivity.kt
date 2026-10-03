package com.daycalculator.dynamic.app

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.Manifest
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.ColorFilter

class MainActivity : ComponentActivity() {
    private var openReminderRequest by mutableStateOf(false)
    private var widgetConfigId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        widgetConfigId = widgetConfigIdFrom(intent)
        if (widgetConfigId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // The launcher has asked MainActivity to configure one specific widget.
            // Start canceled so backing out tells the launcher not to keep the unconfigured widget.
            setResult(RESULT_CANCELED, Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, widgetConfigId
            ))
            setContent {
                WidgetReminderChooser(
                    appWidgetId = widgetConfigId,
                    onDone = { finish() },
                    onCancel = { finish() }
                )
            }
            return
        }

        ReminderScheduler.createChannel(this)
        ReminderScheduler.rescheduleAll(this)
        ReminderScheduler.scheduleYearProgressNotifications(this)
        DateReminderWidget.updateAll(this)
        openReminderRequest = intent.getBooleanExtra(EXTRA_OPEN_REMINDER, false) ||
            intent.getBooleanExtra(EXTRA_OPEN_YEAR_PROGRESS, false)
        setContent {
            DayCalculatorApp(
                openReminder = openReminderRequest,
                onOpenReminderHandled = { openReminderRequest = false }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newWidgetId = widgetConfigIdFrom(intent)
        if (newWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetConfigId = newWidgetId
            setResult(RESULT_CANCELED, Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, widgetConfigId
            ))
            setContent {
                WidgetReminderChooser(
                    appWidgetId = widgetConfigId,
                    onDone = { finish() },
                    onCancel = { finish() }
                )
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_REMINDER, false) ||
            intent.getBooleanExtra(EXTRA_OPEN_YEAR_PROGRESS, false)
        ) {
            openReminderRequest = true
        }
    }

    private fun widgetConfigIdFrom(intent: Intent?): Int {
        if (intent?.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            return AppWidgetManager.INVALID_APPWIDGET_ID
        }
        return intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
    }

    fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        ReminderScheduler.rescheduleAll(this)
    }

    fun requestExactAlarmPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarm = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarm.canScheduleExactAlarms()) {
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }
}

private const val EXTRA_OPEN_REMINDER = "open_reminder"
private const val EXTRA_OPEN_YEAR_PROGRESS = "open_year_progress"

private const val PREFS_NAME = "day_calculator_settings"
private const val THEME_KEY = "theme_mode"
private const val HOLIDAYS_KEY = "custom_holidays"

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MainTab(val label: String) {
    AGE("Age"), DIFFERENCE("Date Difference"), ADD_SUBTRACT("Add / Subtract"), COUNTER("Day Counter"), WEEKDAY("Day of Week"), REMINDER("Date Reminder"), CALENDAR("Calendar"), HISTORY("History"), CHANGELOG("Changelog")
}

data class DateParts(val day: Int?, val month: Int?, val year: Int?)
data class Holiday(val month: Int, val day: Int, val name: String)


enum class HistoryType(val label: String) {
    AGE("Age"), DIFFERENCE("Date Difference"), ADD_SUBTRACT("Add / Subtract"), COUNTER("Day Counter"), WEEKDAY("Day of Week")
}

data class HistoryEntry(
    val id: Long,
    val type: HistoryType,
    val timestamp: Long,
    val summary: String,
    val details: String,
    val payload: String
)

private const val HISTORY_PREFS = "day_calculator_history"
private const val HISTORY_KEY = "entries"
private const val MAX_HISTORY_ENTRIES = 100

private object HistoryStore {
    fun load(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(HISTORY_KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val type = runCatching { HistoryType.valueOf(o.optString("type")) }.getOrNull() ?: continue
                    add(HistoryEntry(o.optLong("id"), type, o.optLong("timestamp"), o.optString("summary"), o.optString("details"), o.optString("payload")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun nextId(context: Context): Long {
        val maxExisting = load(context).maxOfOrNull { it.id } ?: 0L
        return maxOf(System.currentTimeMillis(), maxExisting + 1L)
    }

    fun add(context: Context, entry: HistoryEntry) {
        save(context, (listOf(entry) + load(context)).distinctBy { it.id }.take(MAX_HISTORY_ENTRIES))
    }

    fun delete(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })
    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id); put("type", e.type.name); put("timestamp", e.timestamp)
                put("summary", e.summary); put("details", e.details); put("payload", e.payload)
            })
        }
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).edit().putString(HISTORY_KEY, array.toString()).apply()
    }
}

private fun datePayload(date: LocalDate): JSONObject = JSONObject().put("date", date.toString())
private fun parseDatePayload(payload: String, key: String): LocalDate? = runCatching { LocalDate.parse(JSONObject(payload).optString(key)) }.getOrNull()
private fun datesInHistoryEntry(entry: HistoryEntry): Set<LocalDate> = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").findAll(entry.payload).mapNotNull { runCatching { LocalDate.parse(it.value) }.getOrNull() }.toSet()

data class DateInputState(
    val day: Int? = null,
    val month: Int? = null,
    val year: Int? = null
) {
    fun toDate(): LocalDate? = try {
        if (day == null || month == null || year == null) null
        else LocalDate.of(year, month, day)
    } catch (_: DateTimeException) { null }

    companion object {
        fun from(date: LocalDate) = DateInputState(date.dayOfMonth, date.monthValue, date.year)
    }
}

private val monthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
)

private fun loadThemeMode(context: Context): ThemeMode = when (
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(THEME_KEY, ThemeMode.SYSTEM.name)
) {
    ThemeMode.LIGHT.name -> ThemeMode.LIGHT
    ThemeMode.DARK.name -> ThemeMode.DARK
    else -> ThemeMode.SYSTEM
}

private fun saveThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString(THEME_KEY, mode.name).apply()
    DateReminderWidget.updateAll(context)
}

private fun loadHolidays(context: Context): List<Holiday> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(HOLIDAYS_KEY, "") ?: ""
    return raw.split("\n").mapNotNull { line ->
        val p = line.split("|", limit = 2)
        if (p.size != 2) return@mapNotNull null
        val md = p[0].split("-", limit = 2)
        if (md.size != 2) return@mapNotNull null
        val m = md[0].toIntOrNull() ?: return@mapNotNull null
        val d = md[1].toIntOrNull() ?: return@mapNotNull null
        if (m !in 1..12 || d !in 1..31 || p[1].isBlank()) null else Holiday(m, d, p[1])
    }
}

private fun saveHolidays(context: Context, holidays: List<Holiday>) {
    val raw = holidays.joinToString("\n") { "%02d-%02d|%s".format(it.month, it.day, it.name.replace("|", " ")) }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(HOLIDAYS_KEY, raw).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetReminderChooser(
    appWidgetId: Int,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val themeMode = remember { mutableStateOf(loadThemeMode(context)) }
    val dark = when (themeMode.value) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    var selectedId by remember {
        mutableStateOf(DateReminderWidget.selectedReminderId(context, appWidgetId))
    }
    val reminders = remember { ReminderStore.load(context) }
    val yearProgressEnabled = remember { mutableStateOf(DateReminderWidget.isYearProgressEnabled(context)) }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF151515),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF101010),
            surfaceContainerHigh = Color(0xFF151515),
            surfaceContainerHighest = Color(0xFF1C1C1C),
            onBackground = Color(0xFFF5F5F5),
            onSurface = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFFBDBDBD),
            outline = Color(0xFF444444)
        ) else lightColorScheme(
            primary = Color(0xFF111111),
            onPrimary = Color.White,
            background = Color.White,
            surface = Color.White,
            surfaceVariant = Color(0xFFF5F5F5),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFAFA),
            surfaceContainer = Color(0xFFF7F7F7),
            surfaceContainerHigh = Color(0xFFF3F3F3),
            surfaceContainerHighest = Color(0xFFEFEFEF),
            onBackground = Color(0xFF1C1B1F),
            onSurface = Color(0xFF1C1B1F),
            onSurfaceVariant = Color(0xFF49454F),
            outline = Color(0xFF777777)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Choose reminder") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) { Text("✕") }
                    },
                    actions = {
                        TextButton(
                            enabled = selectedId != null,
                            onClick = {
                                val id = selectedId ?: return@TextButton
                                DateReminderWidget.setSelectedReminder(context, appWidgetId, id)
                                DateReminderWidget.updateAppWidget(
                                    context,
                                    AppWidgetManager.getInstance(context),
                                    appWidgetId
                                )
                                val result = Intent().putExtra(
                                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    appWidgetId
                                )
                                (context as? ComponentActivity)?.setResult(Activity.RESULT_OK, result)
                                onDone()
                            }
                        ) { Text("✓") }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    "Select which reminder this widget should display.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (reminders.isEmpty() && !yearProgressEnabled.value) {
                    Text(
                        "No reminders saved yet. Open DayCalcy and add a reminder first.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (yearProgressEnabled.value) {
                            item(key = DateReminderWidget.YEAR_PROGRESS_ID) {
                                val checked = selectedId == DateReminderWidget.YEAR_PROGRESS_ID
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedId = DateReminderWidget.YEAR_PROGRESS_ID },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = 1.dp
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = checked,
                                            onClick = { selectedId = DateReminderWidget.YEAR_PROGRESS_ID }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Year Progress", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${LocalDate.now().year} • Automatically tracks the current year",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(reminders, key = { it.id }) { reminder ->
                            val checked = reminder.id == selectedId
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedId = reminder.id },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 1.dp
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = checked,
                                        onClick = { selectedId = reminder.id }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            reminder.title,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            "${reminder.date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()))} • ${reminder.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayCalculatorApp(
    openReminder: Boolean = false,
    onOpenReminderHandled: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var themeMode by remember { mutableStateOf(loadThemeMode(context)) }
    var selectedTool by remember { mutableStateOf<MainTab?>(null) }
    var holidays by remember { mutableStateOf(loadHolidays(context)) }
    var showHolidaySettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var historyEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var returnTool by remember { mutableStateOf<MainTab?>(null) }
    var calendarReminderId by remember { mutableStateOf<String?>(null) }
    var calendarMonth by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var calendarSelectedDate by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }

    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    LaunchedEffect(openReminder) {
        if (openReminder) {
            selectedTool = MainTab.REMINDER
            onOpenReminderHandled()
        }
    }

    fun navigateBack() {
        if (returnTool != null) {
            selectedTool = returnTool
            returnTool = null
            historyEntry = null
        } else {
            selectedTool = null
            historyEntry = null
        }
    }

    BackHandler(enabled = selectedTool != null) { navigateBack() }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            onPrimary = androidx.compose.ui.graphics.Color(0xFF000000),
            background = androidx.compose.ui.graphics.Color(0xFF000000),
            surface = androidx.compose.ui.graphics.Color(0xFF000000),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF151515),
            surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF000000),
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF080808),
            surfaceContainer = androidx.compose.ui.graphics.Color(0xFF101010),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF151515),
            surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF1C1C1C),
            onBackground = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
            onSurface = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
            outline = androidx.compose.ui.graphics.Color(0xFF444444)
        ) else lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF111111),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            background = androidx.compose.ui.graphics.Color.White,
            surface = androidx.compose.ui.graphics.Color.White,
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
            surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
            surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFFAFAFA),
            surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF7F7F7),
            surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFF3F3F3),
            surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFEFEFEF),
            onBackground = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
            outline = androidx.compose.ui.graphics.Color(0xFF777777)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        if (selectedTool != null) {
                            IconButton(onClick = { navigateBack() }) {
                                Text("←", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    },
                    title = {
                        Text(
                            selectedTool?.label ?: "DayCalcy",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Text("⋮", style = MaterialTheme.typography.headlineMedium)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                ThemeMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label(true)) },
                                        onClick = {
                                            themeMode = mode
                                            saveThemeMode(context, mode)
                                            menuExpanded = false
                                        },
                                        modifier = Modifier.height(44.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("About") },
                                    onClick = { menuExpanded = false; showAbout = true },
                                    modifier = Modifier.height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (selectedTool == null) {
                    HomeScreen(onSelect = { historyEntry = null; returnTool = null; selectedTool = it })
                } else {
                    when (selectedTool) {
                        MainTab.AGE -> AgeScreen(historyEntry)
                        MainTab.DIFFERENCE -> DateDifferenceScreen(holidays, { showHolidaySettings = true }, historyEntry)
                        MainTab.ADD_SUBTRACT -> AddSubtractScreen(holidays, { showHolidaySettings = true }, historyEntry)
                        MainTab.COUNTER -> DayCounterScreen(holidays, { showHolidaySettings = true }, historyEntry)
                        MainTab.WEEKDAY -> DayOfWeekScreen(historyEntry)
                        MainTab.REMINDER -> DateReminderScreen(
                            darkTheme = darkTheme,
                            initialReminderId = calendarReminderId,
                            onInitialReminderHandled = { calendarReminderId = null }
                        )
                        MainTab.CALENDAR -> CalendarScreen(
                            month = calendarMonth,
                            selectedDate = calendarSelectedDate,
                            onMonthChanged = { calendarMonth = it },
                            onSelectedDateChanged = { calendarSelectedDate = it },
                            onOpenReminder = { reminderId ->
                                returnTool = MainTab.CALENDAR
                                historyEntry = null
                                selectedTool = MainTab.REMINDER
                                // Open the Date Reminder section only; do not enter edit mode.
                                calendarReminderId = reminderId
                            },
                            onOpenHistory = { entry ->
                                if (entry == null) {
                                    historyEntry = null
                                    returnTool = MainTab.CALENDAR
                                    selectedTool = MainTab.HISTORY
                                } else {
                                    historyEntry = entry
                                    returnTool = MainTab.CALENDAR
                                    selectedTool = when (entry.type) {
                                        HistoryType.AGE -> MainTab.AGE
                                        HistoryType.DIFFERENCE -> MainTab.DIFFERENCE
                                        HistoryType.ADD_SUBTRACT -> MainTab.ADD_SUBTRACT
                                        HistoryType.COUNTER -> MainTab.COUNTER
                                        HistoryType.WEEKDAY -> MainTab.WEEKDAY
                                    }
                                }
                            }
                        )
                        MainTab.HISTORY -> HistoryScreen(onReopen = { entry ->
                            historyEntry = entry
                            returnTool = MainTab.HISTORY
                            selectedTool = when (entry.type) {
                                HistoryType.AGE -> MainTab.AGE
                                HistoryType.DIFFERENCE -> MainTab.DIFFERENCE
                                HistoryType.ADD_SUBTRACT -> MainTab.ADD_SUBTRACT
                                HistoryType.COUNTER -> MainTab.COUNTER
                                HistoryType.WEEKDAY -> MainTab.WEEKDAY
                            }
                        })
                        MainTab.CHANGELOG -> ChangelogScreen()
                        null -> Unit
                    }
                }
            }
        }

        if (showAbout) {
            AboutDialog(
                darkTheme = darkTheme,
                onDismiss = { showAbout = false },
                onOpenChangelog = {
                    showAbout = false
                    selectedTool = MainTab.CHANGELOG
                }
            )
        }

        if (showHolidaySettings) {
            HolidaySettingsDialog(
                holidays = holidays,
                onDismiss = { showHolidaySettings = false },
                onSave = {
                    holidays = it.sortedWith(compareBy<Holiday> { h -> h.month }.thenBy { h -> h.day })
                    saveHolidays(context, holidays)
                    showHolidaySettings = false
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(onSelect: (MainTab) -> Unit) {
    Text("Calculate dates easily", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))
    ToolCard("Age Calculator", "Find your exact age", "01", onClick = { onSelect(MainTab.AGE) })
    ToolCard("Date Difference", "Find days between dates", "02", onClick = { onSelect(MainTab.DIFFERENCE) })
    ToolCard("Add / Subtract Date", "Calculate a new date", "03", onClick = { onSelect(MainTab.ADD_SUBTRACT) })
    ToolCard("Day Counter", "Count days from a date", "04", onClick = { onSelect(MainTab.COUNTER) })
    ToolCard("Day of Week", "Find the weekday for any date", "05", onClick = { onSelect(MainTab.WEEKDAY) })
    ToolCard("Date Reminder", "Remember important dates", "06", onClick = { onSelect(MainTab.REMINDER) })

    // Keep the privacy/offline message with the main Home content, as before.
    Text(
        "Works offline • Simple and private",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    )

    // Fixed spacing keeps the Home screen clean and reveals Tools after scrolling.
    Spacer(Modifier.height(85.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.weight(1f))
        Text("Tools", modifier = Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard("Calendar", "Browse dates", R.drawable.ic_calendar, Modifier.weight(1f)) { onSelect(MainTab.CALENDAR) }
        CompactToolCard("History", "Your calculations", R.drawable.ic_history, Modifier.weight(1f)) { onSelect(MainTab.HISTORY) }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, number: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Text(number, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompactToolCard(title: String, subtitle: String, iconRes: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surface) {
                Icon(painterResource(iconRes), contentDescription = title, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(8.dp).size(28.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ThemeMode.label(full: Boolean = false): String = when (this) {
    ThemeMode.SYSTEM -> if (full) "System Default" else "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun DateSelector(
    title: String,
    state: DateInputState,
    onChanged: (DateInputState) -> Unit,
    allowToday: Boolean = true
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(7.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateChoiceBox("Day", state.day?.toString() ?: "—", Modifier.weight(0.85f)) { dialog = "day" }
        DateChoiceBox("Month", state.month?.let { monthNames[it - 1].take(3) } ?: "—", Modifier.weight(1.15f)) { dialog = "month" }
        DateChoiceBox("Year", state.year?.toString() ?: "—", Modifier.weight(1.35f)) { dialog = "year" }
    }
    if (allowToday) {
        TextButton(onClick = { onChanged(DateInputState.from(LocalDate.now())) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Use Today")
        }
    }

    when (dialog) {
        "month" -> SelectionDialog("Select month", monthNames, state.month ?: LocalDate.now().monthValue, { it + 1 }, { dialog = null }) {
            val newMonth = it + 1
            val newDay = state.day?.let { d -> minOf(d, daysInMonth(state.year ?: LocalDate.now().year, newMonth)) }
            onChanged(state.copy(month = newMonth, day = newDay)); dialog = null
        }
        "day" -> {
            val maxDay = daysInMonth(state.year ?: LocalDate.now().year, state.month ?: LocalDate.now().monthValue)
            SelectionDialog("Select day", (1..maxDay).map { it.toString() }, state.day ?: 1, { it + 1 }, { dialog = null }) {
                onChanged(state.copy(day = it + 1)); dialog = null
            }
        }
        "year" -> YearSelectionDialog(state.year ?: LocalDate.now().year, { year ->
            val newDay = state.day?.let { minOf(it, daysInMonth(year, state.month ?: LocalDate.now().monthValue)) }
            onChanged(state.copy(year = year, day = newDay)); dialog = null
        }, { dialog = null })
    }
}

@Composable
private fun DateChoiceBox(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SelectionDialog(title: String, items: List<String>, selected: Int, selectedIndex: (Int) -> Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(items) { index, item ->
                    val actual = selectedIndex(index)
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(index) }.padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = actual == selected, onClick = { onSelect(index) })
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun YearSelectionDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(selected.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select year", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = { Text("Year") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                Text("Enter a 4-digit year, then tap Done.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { text.toIntOrNull()?.takeIf { it in 1..9999 }?.let(onSelect) }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun daysInMonth(year: Int, month: Int): Int = try { LocalDate.of(year, month, 1).lengthOfMonth() } catch (_: Exception) { 31 }

@Composable
fun AgeScreen(historyEntry: HistoryEntry? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = LocalDate.now()
    var dob by remember { mutableStateOf(DateInputState()) }
    var ageAt by remember { mutableStateOf(DateInputState.from(today)) }
    var hasAgeAt by remember { mutableStateOf(true) }
    var calculated by remember { mutableStateOf(false) }

    Text("Age Calculator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Calculate exact age in years, months, weeks, days, hours, minutes and seconds.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
    DateSelector("Date of Birth", dob, { dob = it; calculated = false })
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(hasAgeAt, { hasAgeAt = it; calculated = false })
        Text("Calculate age at a specific date")
    }
    if (hasAgeAt) DateSelector("Age at", ageAt, { ageAt = it; calculated = false }, allowToday = true)
    else TextButton(onClick = { ageAt = DateInputState.from(today); hasAgeAt = true }) { Text("Use today") }

    val birth = dob.toDate()
    val at = if (hasAgeAt) ageAt.toDate() else today
    val ageDatesValid = birth != null && at != null && !birth.isAfter(at)
    LaunchedEffect(historyEntry?.id) {
        if (historyEntry?.type == HistoryType.AGE) {
            parseDatePayload(historyEntry.payload, "dob")?.let { dob = DateInputState.from(it) }
            parseDatePayload(historyEntry.payload, "ageAt")?.let { ageAt = DateInputState.from(it) }
            hasAgeAt = JSONObject(historyEntry.payload).optBoolean("hasAgeAt", true)
            calculated = true
        }
    }
    LaunchedEffect(calculated) {
        if (calculated && historyEntry == null && birth != null && at != null && !birth.isAfter(at)) {
            val period = exactAgePeriod(birth, at)
            val summary = "${period.years} ${unit(period.years, "year")} ${period.months} ${unit(period.months, "month")} ${period.days} ${unit(period.days, "day")}"
            val details = "${formatDate(birth)} → ${formatDate(at)}\n$summary"
            val payload = JSONObject().put("dob", birth.toString()).put("ageAt", at.toString()).put("hasAgeAt", hasAgeAt).toString()
            val id = HistoryStore.nextId(context)
            HistoryStore.add(context, HistoryEntry(id, HistoryType.AGE, id, summary, details, payload))
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { if (ageDatesValid) calculated = true },
        enabled = ageDatesValid,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Calculate") }
    if (birth != null && at != null && calculated) {
        Spacer(Modifier.height(12.dp))
        if (birth.isAfter(at)) ErrorText("Date of birth cannot be after the age-at date.")
        else {
            val period = exactAgePeriod(birth, at)
            val days = ChronoUnit.DAYS.between(birth, at)
            // Birthday is always derived from the Date of Birth, while the countdown
            // is measured from the selected "Age at" date. The weekday is calculated
            // directly from that actual upcoming birthday date (never from the
            // Age-at date), so the displayed weekday cannot drift to the wrong date.
            val next = nextBirthday(birth, at)
            val daysToNext = ChronoUnit.DAYS.between(at, next)
            val untilBirthday = Period.between(at, next)
            val birthdayMonths = untilBirthday.years * 12L + untilBirthday.months
            val birthdayDuration = "${birthdayMonths} ${unit(birthdayMonths, "month")} ${untilBirthday.days} ${unit(untilBirthday.days, "day")}"
            val birthdayDateText = "${formatDate(next)} • ${dayName(next)}"
            val ageText = "${period.years} ${unit(period.years, "year")} ${period.months} ${unit(period.months, "month")} ${period.days} ${unit(period.days, "day")}"
            val ageMonthsText = "or ${period.years * 12L + period.months} ${unit(period.years * 12L + period.months, "month")} ${period.days} ${unit(period.days, "day")}"
            val totalTimeText = "$days ${unit(days, "day")}\n" +
                "${days / 7} ${unit(days / 7, "week")} ${days % 7} ${unit(days % 7, "day")} • " +
                "${days * 24} ${unit(days * 24, "hour")} • ${days * 1440} ${unit(days * 1440, "minute")} • " +
                "${days * 86400} ${unit(days * 86400, "second")}"
            val birthdayText = "$birthdayDuration\n" +
                "$daysToNext ${unit(daysToNext, "day")} total • $birthdayDateText"
            ResultCard("Age", ageText, ageMonthsText)
            ResultCard("Total time", totalTimeText.substringBefore("\n"), totalTimeText.substringAfter("\n"))
            ResultCard("Age-at date", formatDate(at), dayName(at))
            ResultCard("Birthday", birthdayText.substringBefore("\n"), birthdayText.substringAfter("\n"))
            ResultActions(
                "Age: $ageText\n$ageMonthsText\n\nTotal time: ${days} ${unit(days, "day")}\n${days / 7} ${unit(days / 7, "week")} ${days % 7} ${unit(days % 7, "day")} • ${days * 24} ${unit(days * 24, "hour")} • ${days * 1440} ${unit(days * 1440, "minute")} • ${days * 86400} ${unit(days * 86400, "second")}\n\nAge-at date: ${formatDate(at)} • ${dayName(at)}\n\nBirthday: $birthdayDuration\n$daysToNext ${unit(daysToNext, "day")} total • $birthdayDateText"
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { dob = DateInputState(); ageAt = DateInputState.from(today); hasAgeAt = true; calculated = false }, Modifier.fillMaxWidth()) { Text("Clear") }
}

@Composable
fun DateDifferenceScreen(holidays: List<Holiday>, openSettings: () -> Unit, historyEntry: HistoryEntry? = null) {
    // Calculator.net-style workflow: edit inputs first, then explicitly press Calculate.
    var start by remember { mutableStateOf(DateInputState.from(LocalDate.now())) }
    var end by remember { mutableStateOf(DateInputState.from(LocalDate.now())) }
    var includeEnd by remember { mutableStateOf(false) }
    var businessOnly by remember { mutableStateOf(false) }
    var calculated by remember { mutableStateOf(false) }

    fun invalidateResult() {
        calculated = false
    }

    Text("Days Between Two Dates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "Enter your dates, then tap Calculate to see the result.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(14.dp))

    DateSelector(
        "Start Date",
        start,
        {
            start = it
            invalidateResult()
        }
    )
    Spacer(Modifier.height(8.dp))

    DateSelector(
        "End Date",
        end,
        {
            end = it
            invalidateResult()
        }
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = includeEnd,
            onCheckedChange = {
                includeEnd = it
                invalidateResult()
            }
        )
        Text("Include end date")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = businessOnly,
            onCheckedChange = {
                businessOnly = it
                invalidateResult()
            }
        )
        Text("Count business days only")
    }

    TextButton(
        onClick = openSettings,
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
        Text("Holiday settings (${holidays.size})")
    }

    val s = start.toDate()
    val e = end.toDate()
    val datesValid = s != null && e != null && !e.isBefore(s)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(historyEntry?.id) {
        if (historyEntry?.type == HistoryType.DIFFERENCE) {
            val o = JSONObject(historyEntry.payload)
            parseDatePayload(historyEntry.payload, "start")?.let { start = DateInputState.from(it) }
            parseDatePayload(historyEntry.payload, "end")?.let { end = DateInputState.from(it) }
            includeEnd = o.optBoolean("includeEnd", false); businessOnly = o.optBoolean("businessOnly", false); calculated = true
        }
    }
    LaunchedEffect(calculated) {
        if (calculated && historyEntry == null && datesValid) {
            val totalDays = ChronoUnit.DAYS.between(s!!, e!!) + if (includeEnd) 1 else 0
            val summary = "$totalDays days"
            val details = "${formatDate(s)} → ${formatDate(e)}\n$summary"
            val payload = JSONObject().put("start", s.toString()).put("end", e.toString()).put("includeEnd", includeEnd).put("businessOnly", businessOnly).toString()
            val id = HistoryStore.nextId(context)
            HistoryStore.add(context, HistoryEntry(id, HistoryType.DIFFERENCE, id, summary, details, payload))
        }
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { if (datesValid) calculated = true },
        enabled = datesValid,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Calculate")
    }

    if (s != null && e != null && e.isBefore(s)) {
        Spacer(Modifier.height(8.dp))
        ErrorText("End date must be on or after the start date.")
    }

    if (calculated && datesValid) {
        Spacer(Modifier.height(10.dp))

        val totalDays = ChronoUnit.DAYS.between(s!!, e!!) + if (includeEnd) 1 else 0
        val period = if (includeEnd) Period.between(s, e.plusDays(1)) else Period.between(s, e)
        val holidayCounts = countHolidayDatesBetween(s, e, includeEnd, holidays)
        val weekend = countWeekendDays(s, e, includeEnd)
        val weekdays = totalDays - weekend
        val business = (weekdays - holidayCounts.weekdayHolidays).coerceAtLeast(0)
        val displayed = if (businessOnly) business else totalDays

        ResultCard(
            "Result",
            "${period.years} ${unit(period.years, "year")} ${period.months} ${unit(period.months, "month")} ${period.days} ${unit(period.days, "day")}",
            "${formatDate(s)} → ${formatDate(e)}"
        )
        ResultCard(
            "Total",
            "$displayed days",
            "${totalDays / 7} ${unit(totalDays / 7, "week")} ${totalDays % 7} ${unit(totalDays % 7, "day")} • " +
                "${period.years * 12L + period.months} ${unit(period.years * 12L + period.months, "month")} ${period.days} ${unit(period.days, "day")}"
        )
        ResultCard(
            "Workdays",
            "$business business days",
            "$weekdays weekdays • $weekend weekend days • ${holidayCounts.totalHolidays} configured holidays"
        )
        ResultCard(
            "Days of week",
            "${dayName(s)} → ${dayName(e)}",
            "Start: ${formatDate(s)} • End: ${formatDate(e)}"
        )
        ResultActions(
            "Result: ${period.years} ${unit(period.years, "year")} ${period.months} ${unit(period.months, "month")} ${period.days} ${unit(period.days, "day")}\n${formatDate(s)} → ${formatDate(e)}\n\nTotal: $displayed days\n${totalDays / 7} ${unit(totalDays / 7, "week")} ${totalDays % 7} ${unit(totalDays % 7, "day")} • ${period.years * 12L + period.months} ${unit(period.years * 12L + period.months, "month")} ${period.days} ${unit(period.days, "day")}\n\nWorkdays: $business business days\n$weekdays weekdays • $weekend weekend days • ${holidayCounts.totalHolidays} configured holidays\n\nDays of week: ${dayName(s)} → ${dayName(e)}\nStart: ${formatDate(s)} • End: ${formatDate(e)}"
        )
    }

    Spacer(Modifier.height(10.dp))

    OutlinedButton(
        onClick = {
            start = DateInputState.from(LocalDate.now())
            end = DateInputState.from(LocalDate.now())
            includeEnd = false
            businessOnly = false
            calculated = false
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Reset")
    }
}

@Composable
fun AddSubtractScreen(holidays: List<Holiday>, openSettings: () -> Unit, historyEntry: HistoryEntry? = null) {
    var start by remember { mutableStateOf(DateInputState.from(LocalDate.now())) }
    var years by remember { mutableStateOf("0") }; var months by remember { mutableStateOf("0") }
    var weeks by remember { mutableStateOf("0") }; var days by remember { mutableStateOf("0") }
    var add by remember { mutableStateOf(true) }; var business by remember { mutableStateOf(false) }
    var calculated by remember { mutableStateOf(false) }

    Text("Add or Subtract from a Date", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Move a date forward or backward by years, months, weeks and days.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
    DateSelector("Start Date", start, { start = it; calculated = false }, allowToday = true)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AmountField("Years", years, { years = it; calculated = false }, Modifier.weight(1f))
        AmountField("Months", months, { months = it; calculated = false }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AmountField("Weeks", weeks, { weeks = it; calculated = false }, Modifier.weight(1f))
        AmountField("Days", days, { days = it; calculated = false }, Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(add, { add = true; calculated = false }); Text("Add")
        Spacer(Modifier.width(10.dp)); RadioButton(!add, { add = false; calculated = false }); Text("Subtract")
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(business, { business = it; calculated = false }); Text("Apply weeks/days as business days") }
    TextButton(onClick = openSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Holiday settings (${holidays.size})") }

    val date = start.toDate()
    val y = years.toLongOrNull() ?: 0L; val m = months.toLongOrNull() ?: 0L; val w = weeks.toLongOrNull() ?: 0L; val d = days.toLongOrNull() ?: 0L
    val inputsValid = date != null && listOf(y, m, w, d).all { it <= MAX_INPUT_AMOUNT }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(historyEntry?.id) {
        if (historyEntry?.type == HistoryType.ADD_SUBTRACT) {
            val o = JSONObject(historyEntry.payload)
            parseDatePayload(historyEntry.payload, "start")?.let { start = DateInputState.from(it) }
            years = o.optString("years", "0"); months = o.optString("months", "0"); weeks = o.optString("weeks", "0"); days = o.optString("days", "0")
            add = o.optBoolean("add", true); business = o.optBoolean("business", false); calculated = true
        }
    }
    LaunchedEffect(calculated) {
        if (calculated && historyEntry == null && inputsValid) {
            val sign = if (add) 1 else -1
            val initial = runCatching { date!!.plusYears(sign*y).plusMonths(sign*m) }.getOrNull()
            val delta = runCatching { Math.addExact(Math.multiplyExact(w, 7L), d) }.getOrNull()
            val result = if (initial != null && delta != null) runCatching { if (business) addBusinessDays(initial, sign*delta, holidays) else initial.plusDays(sign*delta) }.getOrNull() else null
            if (result != null) {
                val summary = formatDate(result)
                val details = "${formatDate(date)} ${if (add) "+" else "−"} ${y}y ${m}m ${w}w ${d}d\nResult: $summary"
                val payload = JSONObject().put("start", date.toString()).put("years", years).put("months", months).put("weeks", weeks).put("days", days).put("add", add).put("business", business).toString()
                val id = HistoryStore.nextId(context)
                HistoryStore.add(context, HistoryEntry(id, HistoryType.ADD_SUBTRACT, id, summary, details, payload))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { if (inputsValid) calculated = true }, enabled = inputsValid, modifier = Modifier.fillMaxWidth()) { Text("Calculate") }
    if (date != null && calculated) {
        val sign = if (add) 1 else -1
        val tooLarge = listOf(y, m, w, d).any { it > MAX_INPUT_AMOUNT }
        if (tooLarge) {
            ErrorText("Please use values up to $MAX_INPUT_AMOUNT for each field.")
        } else {
            val initialResult: LocalDate? = try {
                date.plusYears(sign * y).plusMonths(sign * m)
            } catch (_: DateTimeException) {
                null
            }
            if (initialResult != null) {
                val delta = try { Math.addExact(Math.multiplyExact(w, 7L), d) } catch (_: ArithmeticException) { Long.MAX_VALUE }
                val dayLimit = if (business) MAX_INPUT_AMOUNT else MAX_INPUT_AMOUNT * 7L
                if (delta > dayLimit) {
                    ErrorText("The requested number of days is too large.")
                } else {
                    val result: LocalDate? = try {
                        if (!business) initialResult.plusDays(sign * delta)
                        else addBusinessDays(initialResult, sign * delta, holidays)
                    } catch (_: DateTimeException) {
                        null
                    }
                    if (result != null) {
                        ResultCard("Result", formatDate(result), dayName(result))
                        ResultActions("Result: ${formatDate(result)}\n${dayName(result)}")
                    }
                    else ErrorText("The requested date is outside the supported date range.")
                }
            } else ErrorText("The requested date is outside the supported date range.")
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { start = DateInputState.from(LocalDate.now()); years="0"; months="0"; weeks="0"; days="0"; add=true; business=false; calculated=false }, Modifier.fillMaxWidth()) { Text("Reset") }
}

@Composable
fun DayCounterScreen(holidays: List<Holiday>, openSettings: () -> Unit, historyEntry: HistoryEntry? = null) {
    var start by remember { mutableStateOf(DateInputState.from(LocalDate.now())) }
    var amount by remember { mutableStateOf("0") }
    var add by remember { mutableStateOf(true) }
    var business by remember { mutableStateOf(false) }
    var calculated by remember { mutableStateOf(false) }

    Text("Day Counter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Count a number of calendar or business days from a date.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
    DateSelector("Start Date", start, { start = it; calculated = false }, allowToday = true)
    Spacer(Modifier.height(8.dp))
    AmountField("Number of days", amount, { amount = it; calculated = false }, Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(add, { add = true; calculated = false }); Text("Add")
        Spacer(Modifier.width(10.dp)); RadioButton(!add, { add = false; calculated = false }); Text("Subtract")
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(business, { business = it; calculated = false }); Text("Count business days only") }
    TextButton(onClick = openSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Holiday settings (${holidays.size})") }

    val date = start.toDate(); val n = amount.toLongOrNull()
    val counterValid = date != null && n != null && n <= MAX_INPUT_AMOUNT
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(historyEntry?.id) {
        if (historyEntry?.type == HistoryType.COUNTER) {
            val o = JSONObject(historyEntry.payload)
            parseDatePayload(historyEntry.payload, "start")?.let { start = DateInputState.from(it) }
            amount = o.optString("amount", "0"); add = o.optBoolean("add", true); business = o.optBoolean("business", false); calculated = true
        }
    }
    LaunchedEffect(calculated) {
        if (calculated && historyEntry == null && date != null && n != null && n <= MAX_INPUT_AMOUNT) {
            val signed = if (add) n else -n
            val result = runCatching { if (business) addBusinessDays(date, signed, holidays) else date.plusDays(signed) }.getOrNull()
            if (result != null) {
                val summary = formatDate(result)
                val details = "${formatDate(date)} ${if (add) "+" else "−"} $n days\nResult: $summary"
                val payload = JSONObject().put("start", date.toString()).put("amount", amount).put("add", add).put("business", business).toString()
                val id = HistoryStore.nextId(context)
                HistoryStore.add(context, HistoryEntry(id, HistoryType.COUNTER, id, summary, details, payload))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { if (counterValid) calculated = true }, enabled = counterValid, modifier = Modifier.fillMaxWidth()) { Text("Calculate") }
    if (date != null && n != null && calculated) {
        if (n > MAX_INPUT_AMOUNT) {
            ErrorText("Please use a value up to $MAX_INPUT_AMOUNT days.")
        } else {
            val signed = if (add) n else -n
            val result = try { if (business) addBusinessDays(date, signed, holidays) else date.plusDays(signed) } catch (_: DateTimeException) { null }
            if (result != null) {
                ResultCard("Result", formatDate(result), dayName(result))
                ResultActions("Result: ${formatDate(result)}\n${dayName(result)}")
            }
            else ErrorText("The requested date is outside the supported date range.")
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { start = DateInputState.from(LocalDate.now()); amount="0"; add=true; business=false; calculated=false }, Modifier.fillMaxWidth()) { Text("Reset") }
}

@Composable
fun DayOfWeekScreen(historyEntry: HistoryEntry? = null) {
    var date by remember { mutableStateOf(DateInputState.from(LocalDate.now())) }
    var calculated by remember { mutableStateOf(false) }
    Text("Day of the Week", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Find the weekday for any date.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
    DateSelector("Date", date, { date = it; calculated = false }, allowToday = true)
    Spacer(Modifier.height(8.dp))
    val validDate = date.toDate() != null
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(historyEntry?.id) {
        if (historyEntry?.type == HistoryType.WEEKDAY) {
            parseDatePayload(historyEntry.payload, "date")?.let { date = DateInputState.from(it) }
            calculated = true
        }
    }
    LaunchedEffect(calculated) {
        if (calculated && historyEntry == null && validDate) {
            val selectedDate = date.toDate()!!
            val summary = dayName(selectedDate)
            val details = "${formatDate(selectedDate)}\n$summary"
            val id = HistoryStore.nextId(context)
            HistoryStore.add(context, HistoryEntry(id, HistoryType.WEEKDAY, id, summary, details, datePayload(selectedDate).toString()))
        }
    }
    Button(onClick = { if (validDate) calculated = true }, enabled = validDate, modifier = Modifier.fillMaxWidth()) { Text("Calculate") }
    if (calculated) date.toDate()?.let { selectedDate ->
        ResultCard("Day", dayName(selectedDate), formatDate(selectedDate))
        InfoResultCard("Day of Year", selectedDate.dayOfYear.toString(), "of ${selectedDate.lengthOfYear()} days")
        val isoWeek = selectedDate.get(WeekFields.ISO.weekOfWeekBasedYear())
        val isoWeeksInYear = selectedDate.range(WeekFields.ISO.weekOfWeekBasedYear()).maximum
        InfoResultCard("Week of Year", isoWeek.toString(), "of $isoWeeksInYear weeks")
        val daysLeft = (selectedDate.lengthOfYear() - selectedDate.dayOfYear).toLong()
        InfoResultCard("Days Left in Year", daysLeft.toString(), if (daysLeft == 1L) "day remaining" else "days remaining")
        ResultActions(
            "Day: ${dayName(selectedDate)}\n${formatDate(selectedDate)}\n\nDay of Year: ${selectedDate.dayOfYear} of ${selectedDate.lengthOfYear()} days\n\nWeek of Year: $isoWeek of $isoWeeksInYear weeks\n\nDays Left in Year: $daysLeft ${if (daysLeft == 1L) "day remaining" else "days remaining"}"
        )
    }
}

private fun defaultReminderTime(now: LocalDateTime = LocalDateTime.now()): LocalTime {
    // The picker works in whole minutes. Start the Add Reminder form at the
    // next selectable minute so a new same-day reminder is immediately valid.
    return now.withSecond(0).withNano(0).plusMinutes(1).toLocalTime()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateReminderScreen(
    darkTheme: Boolean,
    initialReminderId: String? = null,
    onInitialReminderHandled: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? MainActivity
    val today = LocalDate.now()
    var reminders by remember { mutableStateOf(ReminderStore.load(context)) }
    var yearProgressEnabled by remember { mutableStateOf(DateReminderWidget.isYearProgressEnabled(context)) }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(DateInputState.from(today)) }
    var time by remember { mutableStateOf(defaultReminderTime()) }
    var repeatsYearly by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var highlightedReminderId by remember { mutableStateOf<String?>(null) }
    val initialReminderRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    fun openAddForm() {
        title = ""
        val currentDate = LocalDate.now()
        val nextTime = defaultReminderTime()
        date = DateInputState.from(if (nextTime < LocalTime.now()) currentDate.plusDays(1) else currentDate)
        time = nextTime
        repeatsYearly = false
        editingId = null
        showForm = true
    }

    fun openEditForm(reminder: DateReminder) {
        title = reminder.title
        date = DateInputState.from(reminder.date)
        time = reminder.time
        repeatsYearly = reminder.repeatsYearly
        editingId = reminder.id
        showForm = true
    }

    LaunchedEffect(initialReminderId, reminders) {
        val targetId = initialReminderId ?: return@LaunchedEffect
        if (reminders.any { it.id == targetId }) {
            // Calendar reminder navigation opens the Date Reminder section, then brings
            // the exact tapped reminder into view and briefly highlights it.
            highlightedReminderId = targetId
            kotlinx.coroutines.delay(100L)
            initialReminderRequester.bringIntoView()
            kotlinx.coroutines.delay(1800L)
            if (highlightedReminderId == targetId) highlightedReminderId = null
        }
        onInitialReminderHandled()
    }

    fun formattedTime(value: LocalTime): String = value.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

    fun totalDaysText(reminder: DateReminder): String {
        val target = reminderTarget(reminder, LocalDateTime.now())
        val totalDays = ReminderDateMath.totalDays(today, target.toLocalDate())
        return if (totalDays < 0) "Date passed" else "Total: $totalDays ${unit(totalDays, "day")}"
    }

    fun weekdayText(reminder: DateReminder): String =
        reminderTarget(reminder, LocalDateTime.now()).toLocalDate().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())

    fun reminderValidationMessage(): String? {
        val currentDate = LocalDate.now()
        val selected = date.toDate() ?: return "Please enter a valid reminder date."
        if (repeatsYearly) return null
        if (selected.isBefore(currentDate)) {
            return "Past dates can't be used for reminders. Please choose today or a future date."
        }
        if (selected.isEqual(currentDate) && !time.isAfter(LocalTime.now())) {
            return "This time has already passed today. Choose a later time or a future date."
        }
        return null
    }

    fun countdownText(reminder: DateReminder): String {
        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val target = reminderTarget(reminder, now.toLocalDateTime()).atZone(zone)
        return ReminderDateMath.preciseCountdown(now, target)
    }

    Text("Date Reminder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Save an important date and get a notification when it arrives.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Year Progress Reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Shows the current year progress in the existing widget. No date or time is needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = yearProgressEnabled,
                onCheckedChange = { enabled ->
                    yearProgressEnabled = enabled
                    DateReminderWidget.setYearProgressEnabled(context, enabled)
                }
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    Button(onClick = { openAddForm() }, modifier = Modifier.fillMaxWidth()) { Text("Add Reminder") }
    Spacer(Modifier.height(12.dp))

    if (reminders.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No reminders yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Add a birthday, anniversary, expiry date or any important date.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        Text("Saved reminders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        reminders.forEach { reminder ->
            val isHighlighted = highlightedReminderId == reminder.id
            val highlightColor = if (reminder.repeatsYearly) {
                Color(0xFF7E57C2)
            } else {
                Color(0xFFFFA000)
            }
            val animatedHighlight = animateColorAsState(
                targetValue = if (isHighlighted) highlightColor.copy(alpha = 0.10f) else Color.Transparent,
                animationSpec = tween(durationMillis = 260),
                label = "reminderHighlight"
            )
            val animatedBorder = animateColorAsState(
                targetValue = if (isHighlighted) highlightColor.copy(alpha = 0.70f) else Color.Transparent,
                animationSpec = tween(durationMillis = 260),
                label = "reminderHighlightBorder"
            )
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .then(if (initialReminderId == reminder.id) Modifier.bringIntoViewRequester(initialReminderRequester) else Modifier),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isHighlighted) {
                        animatedHighlight.value.compositeOver(MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = if (isHighlighted) {
                    androidx.compose.foundation.BorderStroke(1.2.dp, animatedBorder.value)
                } else null
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(reminder.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(formatDate(reminder.date), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "🔔 ${formattedTime(reminder.time)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            countdownText(reminder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (reminder.repeatsYearly) {
                                val next = reminderTarget(reminder, LocalDateTime.now())
                                "Every year • Next: ${formatDate(next.toLocalDate())} • ${weekdayText(reminder)}"
                            } else {
                                "${totalDaysText(reminder)} • ${weekdayText(reminder)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { openEditForm(reminder) }) { Text("Edit") }
                        TextButton(onClick = {
                            ReminderScheduler.cancel(context, reminder.id)
                            reminders = reminders.filterNot { it.id == reminder.id }
                            ReminderStore.save(context, reminders)
                            DateReminderWidget.updateAll(context)
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showForm) {
        AlertDialog(
            onDismissRequest = { showForm = false },
            title = { Text(if (editingId == null) "Add reminder" else "Edit reminder", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(60) },
                        label = { Text("Reminder name") },
                        placeholder = { Text("Birthday, expiry date, trip…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    DateSelector("Reminder date", date, { date = it }, allowToday = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reminder time: ${formattedTime(time)}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = repeatsYearly, onCheckedChange = { repeatsYearly = it })
                        Column {
                            Text("Repeat every year", style = MaterialTheme.typography.bodyMedium)
                            if (repeatsYearly) {
                                Text(
                                    "Useful for birthdays and anniversaries. The year is kept as the original date; the reminder uses the next occurrence.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    reminderValidationMessage()?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ $message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                    val selected = date.toDate()
                    if (title.isNotBlank() && selected != null && (repeatsYearly || !selected.isBefore(today))) {
                        val oldId = editingId
                        if (oldId != null) ReminderScheduler.cancel(context, oldId)
                        val reminder = DateReminder(oldId ?: newReminderId(), title.trim(), selected, time, repeatsYearly)
                        reminders = (reminders.filterNot { it.id == reminder.id } + reminder)
                            .sortedWith(compareBy<DateReminder> { it.date }.thenBy { it.time })
                        ReminderStore.save(context, reminders)
                        ReminderScheduler.schedule(context, reminder)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val alarm = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                            if (!alarm.canScheduleExactAlarms()) activity?.requestExactAlarmPermissionIfNeeded()
                        }
                        DateReminderWidget.updateAll(context)
                        activity?.requestNotificationPermissionIfNeeded()
                        title = ""
                        val currentDate = LocalDate.now()
                        val nextTime = defaultReminderTime()
                        date = DateInputState.from(if (nextTime < LocalTime.now()) currentDate.plusDays(1) else currentDate)
                        time = nextTime
                        repeatsYearly = false
                        editingId = null
                        showForm = false
                    }
                },
                enabled = title.isNotBlank() && date.toDate() != null && reminderValidationMessage() == null
            ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showForm = false }) { Text("Cancel") } }
        )
    }

    if (showTimePicker) {
        val pickerTheme = if (darkTheme) R.style.Theme_DayCalculator_TimePicker_Dark else R.style.Theme_DayCalculator_TimePicker_Light
        val pickerContext = remember(darkTheme) { ContextThemeWrapper(context, pickerTheme) }
        DisposableEffect(showTimePicker, darkTheme) {
            val dialog = android.app.TimePickerDialog(
                pickerContext,
                { _, hour, minute ->
                    // Commit hour and minute together from the picker.
                    time = LocalTime.of(hour, minute)
                    showTimePicker = false
                },
                time.hour,
                time.minute,
                false
            )
            dialog.setOnDismissListener { showTimePicker = false }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }
}


@Composable
private fun CalendarScreen(
    month: LocalDate,
    selectedDate: LocalDate?,
    onMonthChanged: (LocalDate) -> Unit,
    onSelectedDateChanged: (LocalDate?) -> Unit,
    onOpenReminder: (String) -> Unit,
    onOpenHistory: (HistoryEntry?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = LocalDate.now()
    val selected = selectedDate
    var history by remember { mutableStateOf(HistoryStore.load(context)) }
    var reminders by remember { mutableStateOf(ReminderStore.load(context)) }
    var showMonthYearPicker by remember { mutableStateOf(false) }
    var pickerYear by remember { mutableStateOf(today.year) }

    LaunchedEffect(Unit) {
        history = HistoryStore.load(context)
        reminders = ReminderStore.load(context)
    }

    val historyDates = history.flatMap { datesInHistoryEntry(it) }.toSet()
    val firstDay = month.dayOfWeek.value
    val days = month.lengthOfMonth()
    fun reminderOccursOn(date: LocalDate, reminder: DateReminder): Boolean =
        if (reminder.repeatsYearly) {
            date.monthValue == reminder.date.monthValue && date.dayOfMonth == reminder.date.dayOfMonth
        } else {
            date == reminder.date
        }
    val historyDotColor = Color(0xFF5B8DEF)
    val reminderDotColor = Color(0xFFD98B2B)
    val yearlyReminderDotColor = Color(0xFF8E63C7)

    fun moveMonth(delta: Long) {
        onMonthChanged(month.plusMonths(delta))
        // Month navigation must never auto-select the same day number in the new month.
        onSelectedDateChanged(null)
    }

    fun chooseMonthYear(year: Int, monthValue: Int) {
        onMonthChanged(LocalDate.of(year, monthValue, 1))
        onSelectedDateChanged(null)
        showMonthYearPicker = false
    }

    Text("Calendar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Browse dates, reminders and calculation history.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { moveMonth(-1) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                    modifier = Modifier.weight(1f).clickable {
                        pickerYear = month.year
                        showMonthYearPicker = true
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = { moveMonth(1) }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("M","T","W","T","F","S","S").forEach {
                    Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            val totalCells = ((firstDay - 1 + days + 6) / 7) * 7
            for (weekStart in 0 until totalCells step 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (cell in weekStart until weekStart + 7) {
                        val day = cell - (firstDay - 1) + 1
                        if (day !in 1..days) {
                            Spacer(Modifier.weight(1f).height(46.dp))
                        } else {
                            val date = month.withDayOfMonth(day)
                            val isSelected = date == selected
                            val isToday = date == today
                            val hasHistory = date in historyDates
                            val hasYearlyReminder = reminders.any { it.repeatsYearly && reminderOccursOn(date, it) }
                            val hasOneTimeReminder = reminders.any { !it.repeatsYearly && reminderOccursOn(date, it) }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectedDateChanged(date) }
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            day.toString(),
                                            color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Row(
                                            Modifier.align(Alignment.BottomCenter),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            if (hasHistory) Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp))) {
                                                Canvas(Modifier.fillMaxSize()) { drawCircle(historyDotColor) }
                                            }
                                            if (hasOneTimeReminder) Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp))) {
                                                Canvas(Modifier.fillMaxSize()) { drawCircle(reminderDotColor) }
                                            }
                                            if (hasYearlyReminder) Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp))) {
                                                Canvas(Modifier.fillMaxSize()) { drawCircle(yearlyReminderDotColor) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    onMonthChanged(today.withDayOfMonth(1))
                    onSelectedDateChanged(today)
                }) { Text("Today") }
            }
        }
    }

    if (showMonthYearPicker) {
        AlertDialog(
            onDismissRequest = { showMonthYearPicker = false },
            title = { Text("Select month & year") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { pickerYear-- }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                        Text(pickerYear.toString(), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { pickerYear++ }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Spacer(Modifier.height(6.dp))
                    for (row in 0 until 4) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (column in 0 until 3) {
                                val monthValue = row * 3 + column + 1
                                val isCurrent = pickerYear == month.year && monthValue == month.monthValue
                                TextButton(
                                    onClick = { chooseMonthYear(pickerYear, monthValue) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        monthNames[monthValue - 1].take(3),
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMonthYearPicker = false }) { Text("Cancel") } }
        )
    }

    val selectedDate = selected
    if (selectedDate != null) {
        val selectedHistory = history.filter { selectedDate in datesInHistoryEntry(it) }
        val selectedReminders = reminders.filter { reminderOccursOn(selectedDate, it) }
        val yearLength = selectedDate.lengthOfYear().toDouble()
        val yearProgress = (selectedDate.dayOfYear / yearLength * 100.0).coerceIn(0.0, 100.0)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
                Text(formatDate(selectedDate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(dayName(selectedDate), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                val week = selectedDate.get(WeekFields.ISO.weekOfWeekBasedYear())
                Text(
                    "Day ${selectedDate.dayOfYear} of ${selectedDate.lengthOfYear()} • Week $week",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${selectedDate.lengthOfYear() - selectedDate.dayOfYear} days remaining",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Text("Reminders", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (selectedReminders.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("No reminders", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Spacer(Modifier.height(2.dp))
                    selectedReminders.forEach { reminder ->
                        TextButton(
                            onClick = { onOpenReminder(reminder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 5.dp, horizontal = 0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "•",
                                    color = if (reminder.repeatsYearly) yearlyReminderDotColor else reminderDotColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(reminder.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (reminder.repeatsYearly) "Every year • ${reminder.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))}" else reminder.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Text("Calculation History", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (selectedHistory.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("No calculation history", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Spacer(Modifier.height(2.dp))
                    selectedHistory.take(3).forEach { entry ->
                        TextButton(
                            onClick = { onOpenHistory(entry) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 5.dp, horizontal = 0.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("•", color = historyDotColor, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.type.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                    Text(entry.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (selectedHistory.size > 3) {
                        TextButton(onClick = { onOpenHistory(null) }, contentPadding = PaddingValues(vertical = 3.dp, horizontal = 0.dp)) {
                            Text("View all history")
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Text("Year Progress", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("${String.format(Locale.getDefault(), "%.1f", yearProgress)}% of the year completed", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${String.format(Locale.getDefault(), "%.1f", 100.0 - yearProgress)}% remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun entryDate(entry: HistoryEntry): LocalDate =
    Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun HistoryScreen(onReopen: (HistoryEntry) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by remember { mutableStateOf(HistoryStore.load(context)) }
    var filter by remember { mutableStateOf<HistoryType?>(null) }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    val visible = entries.filter { filter == null || it.type == filter }
    val today = LocalDate.now()
    val todayEntries = visible.filter { entryDate(it) == today }
    val yesterdayEntries = visible.filter { entryDate(it) == today.minusDays(1) }
    val earlierEntries = visible.filter { entryDate(it) < today.minusDays(1) }
    Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Your recent date calculations are saved on this device.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
        HistoryType.entries.forEach { type -> FilterChip(selected = filter == type, onClick = { filter = if (filter == type) null else type }, label = { Text(type.label) }) }
    }
    Spacer(Modifier.height(8.dp))
    if (entries.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { HistoryStore.clear(context); entries = emptyList() }) { Text("Clear all") }
    }
    if (visible.isEmpty()) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No calculation history", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Your recent date calculations will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    } else {
        @Composable
        fun historySection(title: String, sectionEntries: List<HistoryEntry>) {
            if (sectionEntries.isEmpty()) return
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            sectionEntries.forEach { entry ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = entry }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.type.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(entry.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text(formatDate(entryDate(entry)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(entry.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
        historySection("Today", todayEntries)
        historySection("Yesterday", yesterdayEntries)
        historySection("Earlier", earlierEntries)
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.type.label) }, text = { Text(entry.details) }, confirmButton = { TextButton(onClick = { selected = null; onReopen(entry) }) { Text("Open") } }, dismissButton = { TextButton(onClick = { HistoryStore.delete(context, entry.id); entries = HistoryStore.load(context); selected = null }) { Text("Delete") } })
    }
}

@Composable
private fun AmountField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, { onValueChange(it.filter(Char::isDigit).take(7)) }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

@Composable
private fun ErrorText(text: String) { Text(text, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) }

@Composable
private fun InfoResultCard(title: String, main: String, sub: String) {
    Card(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(sub, style = MaterialTheme.typography.bodyMedium)
                }
                Text(main, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ResultActions(text: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = { copyResult(context, text) }) { Text("Copy") }
        TextButton(onClick = { shareResult(context, text) }) { Text("Share") }
    }
}

private fun copyResult(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DayCalcy result", text))
    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

private fun shareResult(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share result"))
}

@Composable
fun ResultCard(title: String, main: String, sub: String) {
    Card(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(main, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


@Composable
private fun AboutDialog(darkTheme: Boolean, onDismiss: () -> Unit, onOpenChangelog: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentYear = LocalDate.now().year
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val releasesUrl = "https://github.com/Veevek1/Day-Calculator/releases"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_icon_about),
                    contentDescription = "DayCalcy icon",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(8.dp))
                Text("DayCalcy", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Veevek1")))
                            } catch (_: Exception) { }
                        },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (darkTheme) Color.Black else Color.White
                        ) {
                            Image(
                                painter = painterResource(id = if (darkTheme) R.drawable.ic_telegram_white else R.drawable.ic_telegram_black),
                                contentDescription = "Telegram",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Telegram", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl)))
                            } catch (_: Exception) { }
                        },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (darkTheme) Color.Black else Color.White
                        ) {
                            Image(
                                painter = painterResource(id = if (darkTheme) R.drawable.ic_github_white else R.drawable.ic_github_black),
                                contentDescription = "GitHub",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("GitHub", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("For updates, check GitHub.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("Thank you for using DayCalcy!", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("Version: $versionName ($versionCode)", style = MaterialTheme.typography.bodySmall)
                Text("© $currentYear Vivek", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenChangelog)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_changelog),
                        contentDescription = "Changelog",
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Changelog",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}
@Composable
private fun ChangelogScreen() {
    val entries = listOf(
        "3.42.8" to listOf(
            "Calendar" to listOf(
                "Improved selected-date information with matching Reminders, Calculation History and Year Progress sections.",
                "Added separate reminder and history markers, including yearly reminder markers on the same month and day.",
                "Reminder and history items now open their existing screens without changing the calendar date selection behavior."
            ),
            "Navigation" to listOf(
                "Returning from a reminder or reopened calculation now returns to the screen it was opened from."
            )
        ),
        "3.42.7" to listOf(
            "New" to listOf(
                "Added Date Calculation History for recent calculations.",
                "Added a Calendar view with reminder and history markers.",
                "Renamed the app to DayCalcy."
            ),
            "Tools" to listOf(
                "Added compact Calendar and History cards to the home screen.",
                "Added theme-aware Calendar and History icons for Light and Dark mode."
            ),
            "Other" to listOf(
                "Updated the proprietary license to allow use and sharing of the official unmodified app.",
                "Kept DayCalcy completely offline."
            )
        ),
        "3.42.6" to listOf(
            "App Icon" to listOf(
                "Introduced the new Day Calculator calendar and clock icon.",
                "Updated the About section to use the same small app logo.",
                "Prepared the icon as a clean square source for launcher theming."
            ),
            "Reminders" to listOf(
                "Added Done and Snooze 10 min actions to reminder notifications.",
                "Snoozing keeps the saved reminder date unchanged."
            ),
            "Results" to listOf(
                "Added one Copy and Share action for each complete calculation result."
            ),
            "About" to listOf(
                "Refined the Changelog item with a small inline history icon and no arrow."
            ),
            "Stability" to listOf(
                "Continued date, reminder, widget and Year Progress checks."
            )
        ),
        "3.42.5" to listOf(
            "Reminder Notifications" to listOf(
                "Improved reminder notification information.",
                "Added clearer Today, Tomorrow, and In X days wording.",
                "Added human-readable dates and weekdays.",
                "Improved expanded notification content."
            ),
            "Smart Reminders" to listOf(
                "Clear reminder titles can receive a more relevant notification style.",
                "Unrecognized reminders keep the standard notification."
            ),
            "Year Progress" to listOf(
                "Added special year-end and New Year notifications.",
                "Added positive messages that vary by year."
            ),
            "Reminders" to listOf(
                "Deleting a reminder now also cancels its posted notification.",
                "Improved reminder scheduling and cancellation reliability."
            ),
            "About" to listOf(
                "Changed the About title to Day Calculator.",
                "Removed the Created by Vivek text.",
                "Added an in-app Changelog."
            )
        ),
        "3.42.4" to listOf(
            "Year Progress" to listOf(
                "Refined Year Progress widgets.",
                "Improved the 2×2 widget.",
                "Simplified the 4×2 widget."
            ),
            "Widgets" to listOf(
                "Fixed widget backgrounds to follow Day Calculator's selected Light/Dark theme."
            ),
            "Reminders" to listOf(
                "Improved alarm request ID handling to avoid collisions.",
                "Improved fallback handling when Exact Alarm access is unavailable.",
                "Cleaned up migration handling for older reminder alarms."
            ),
            "Other" to listOf(
                "Refreshed the About section with Telegram and GitHub links.",
                "GitHub link now opens the Day Calculator Releases page.",
                "Continued stability and correctness improvements."
            )
        )
    )

    // The parent content container already provides vertical scrolling.
    // Keeping this screen non-scrollable avoids nested vertical-scroll measurement
    // errors when the Changelog is opened from the About dialog.
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        entries.forEachIndexed { index, (version, sections) ->
            if (index > 0) Spacer(Modifier.height(22.dp))
            Text(version, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            sections.forEach { (section, bullets) ->
                Text(section, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                bullets.forEach { bullet ->
                    Text("• $bullet", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun HolidaySettingsDialog(holidays: List<Holiday>, onDismiss: () -> Unit, onSave: (List<Holiday>) -> Unit) {
    var working by remember { mutableStateOf(holidays) }
    var name by remember { mutableStateOf("") }
    var month by remember { mutableIntStateOf(1) }
    var day by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Holiday settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Add dates to exclude from business-day counts. No holidays are assumed by default.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Holiday name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonthMenu(month, {
                        month = it
                        val maxDay = if (it == 2) 29 else daysInMonth(2026, it)
                        day = minOf(day, maxDay)
                    }, Modifier.weight(1f))
                    DayMenu(day, { day = it }, if (month == 2) 29 else daysInMonth(2026, month), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (name.isNotBlank()) { working = (working + Holiday(month, day, name.trim())).distinctBy { "${it.month}-${it.day}-${it.name}" }; name = "" }
                }, Modifier.fillMaxWidth()) { Text("Add holiday") }
                Spacer(Modifier.height(8.dp))
                working.forEachIndexed { index, h ->
                    ListItem(headlineContent = { Text("${monthNames[h.month - 1].take(3)} ${h.day} — ${h.name}") }, trailingContent = { TextButton(onClick = { working = working.filterIndexed { i, _ -> i != index } }) { Text("Remove") } })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(working) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun MonthMenu(value: Int, onValue: (Int) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(monthNames[value - 1].take(3))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            // Keep this as one bounded, scrollable column. A LazyColumn nested
            // inside DropdownMenu can be measured with an unbounded height and
            // crash when the menu is opened on some Compose versions.
            Column(
                modifier = Modifier
                    .widthIn(min = 150.dp, max = 220.dp)
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                monthNames.forEachIndexed { i, n ->
                    DropdownMenuItem(
                        text = { Text(n) },
                        onClick = {
                            onValue(i + 1)
                            open = false
                        }
                    )
                }
            }
        }
    }
}

@Composable private fun DayMenu(value: Int, onValue: (Int) -> Unit, max: Int, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(value.toString()) }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                (1..max).forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n.toString()) },
                        onClick = {
                            onValue(n)
                            open = false
                        }
                    )
                }
            }
        }
    }
}

private fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()))
private fun dayName(date: LocalDate): String = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())

private fun exactAgePeriod(dob: LocalDate, at: LocalDate): Period {
    // A Feb 29 birthday is observed on Feb 28 in non-leap years.
    // Therefore 29 Feb 2024 -> 28 Feb 2025 is exactly 1 year.
    if (dob.monthValue == 2 && dob.dayOfMonth == 29 &&
        at.dayOfMonth == 28 && at.monthValue == 2 && !java.time.Year.isLeap(at.year.toLong()) &&
        at.year > dob.year
    ) {
        return Period.ofYears(at.year - dob.year)
    }
    return Period.between(dob, at)
}

private fun unit(value: Long, singular: String): String = if (value == 1L) singular else "${singular}s"
private fun unit(value: Int, singular: String): String = if (value == 1) singular else "${singular}s"

private fun nextBirthday(dob: LocalDate, at: LocalDate): LocalDate {
    fun candidate(year: Int): LocalDate = if (dob.monthValue == 2 && dob.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28) else dob.withYear(year)
    var next = candidate(at.year)
    if (next.isBefore(at)) next = candidate(at.year + 1)
    return next
}

private data class HolidayCounts(val totalHolidays: Long, val weekdayHolidays: Long)

private fun countHolidayDatesBetween(start: LocalDate, end: LocalDate, includeEnd: Boolean, holidays: List<Holiday>): HolidayCounts {
    if (holidays.isEmpty()) return HolidayCounts(0, 0)
    val last = if (includeEnd) end else end.minusDays(1)
    if (last.isBefore(start)) return HolidayCounts(0, 0)

    var total = 0L
    var weekdays = 0L
    val firstYear = start.year.toLong()
    val lastYear = last.year.toLong()

    for (h in holidays.distinctBy { it.month to it.day }) {
        if (firstYear == lastYear) {
            val d = safeHolidayDate(firstYear, h)
            if (d != null && !d.isBefore(start) && !d.isAfter(last)) {
                total++
                if (isWeekday(d)) weekdays++
            }
            continue
        }

        // Handle the boundary years exactly.
        val firstDate = safeHolidayDate(firstYear, h)
        if (firstDate != null && !firstDate.isBefore(start) && !firstDate.isAfter(last)) {
            total++
            if (isWeekday(firstDate)) weekdays++
        }
        val lastDate = safeHolidayDate(lastYear, h)
        if (lastDate != null && !lastDate.isBefore(start) && !lastDate.isAfter(last)) {
            total++
            if (isWeekday(lastDate)) weekdays++
        }

        // Count complete years using the Gregorian 400-year cycle. This is
        // important for Feb 29: it occurs only in leap years, not every year.
        val firstFull = firstYear + 1L
        val lastFull = lastYear - 1L
        if (firstFull <= lastFull) {
            val fullYears = lastFull - firstFull + 1L
            val cycles = fullYears / 400L
            val rem = fullYears % 400L
            val occurrences400 = holidayOccurrencesIn400Years(h.month, h.day)
            total += cycles * occurrences400
            weekdays += cycles * weekdayOccurrencesIn400Years(h.month, h.day)

            var y = firstFull + cycles * 400L
            repeat(rem.toInt()) {
                val d = safeHolidayDate(y, h)
                if (d != null) {
                    total++
                    if (isWeekday(d)) weekdays++
                }
                y++
            }
        }
    }
    return HolidayCounts(total, weekdays)
}

private fun isWeekday(date: LocalDate): Boolean =
    date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

private fun holidayOccurrencesIn400Years(month: Int, day: Int): Long =
    if (month == 2 && day == 29) 97L else 400L

private fun safeHolidayDate(year: Long, holiday: Holiday): LocalDate? = try {
    if (year !in 1L..999_999_999L) null else LocalDate.of(year.toInt(), holiday.month, holiday.day)
} catch (_: DateTimeException) { null }

private fun weekdayOccurrencesIn400Years(month: Int, day: Int): Long {
    var count = 0L
    for (year in 1..400) {
        val d = safeHolidayDate(year.toLong(), Holiday(month, day, ""))
        if (d != null && d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) count++
    }
    return count
}

private fun countWeekendDays(start: LocalDate, end: LocalDate, includeEnd: Boolean): Long {
    val last = if (includeEnd) end else end.minusDays(1)
    if (last.isBefore(start)) return 0L

    // Count complete weeks mathematically instead of iterating through every date.
    val totalDays = ChronoUnit.DAYS.between(start, last) + 1L
    val completeWeeks = totalDays / 7L
    val remainder = totalDays % 7L
    var weekends = completeWeeks * 2L

    var day = start.dayOfWeek.value
    repeat(remainder.toInt()) {
        if (day == DayOfWeek.SATURDAY.value || day == DayOfWeek.SUNDAY.value) weekends++
        day = if (day == 7) 1 else day + 1
    }
    return weekends
}

private fun isBusinessDay(date: LocalDate, holidays: List<Holiday>): Boolean =
    date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && holidays.none { it.month == date.monthValue && it.day == date.dayOfMonth }

private const val MAX_INPUT_AMOUNT = 1_000_000L

private fun addBusinessDays(start: LocalDate, amount: Long, holidays: List<Holiday>): LocalDate {
    if (amount == 0L) return start
    require(kotlin.math.abs(amount) <= MAX_INPUT_AMOUNT)

    // Exact semantics: the start date is never counted. Move one calendar day
    // at a time in the requested direction and count only business days.
    // This correctly handles starts on weekends and custom holidays in both
    // addition and subtraction directions.
    val step = if (amount > 0L) 1L else -1L
    var remaining = kotlin.math.abs(amount)
    var date = start
    while (remaining > 0L) {
        date = date.plusDays(step)
        if (isBusinessDay(date, holidays)) remaining--
    }
    return date
}
