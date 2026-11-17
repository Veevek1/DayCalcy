package com.daycalculator.dynamic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A reminder chosen in the popup that has not been saved yet. */
internal data class PendingTodoReminder(
    val date: LocalDate,
    val time: LocalTime,
    val repeat: ReminderRepeat,
    val interval: Int
)

/** A lightweight dialog Activity: the launcher (or the app) remains visible and dimmed behind it. */
class TodoQuickAddActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun promptExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!alarm.canScheduleExactAlarms()) {
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // Draw edge-to-edge so the panel extends under the navigation bar (no see-through strip).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        val dark = when (getSharedPreferences("day_calculator_settings", MODE_PRIVATE)
            .getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> false
            "DARK", "AMOLED" -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val amoled = getSharedPreferences("day_calculator_settings", MODE_PRIVATE)
            .getString("theme_mode", "SYSTEM") == "AMOLED"
        val panel = if (dark) Color(0xFF202020) else Color(0xFFF7F7F7)
        val field = if (dark) Color(0xFF353535) else Color(0xFFEAEAEA)
        val foreground = if (dark) Color(0xFFF5F5F5) else Color(0xFF202020)
        val muted = if (dark) Color(0xFFBDBDBD) else Color(0xFF666666)
        val accent = Color(0xFF4CAF50)

        setContent {
            val ctx = this@TodoQuickAddActivity
            val colorScheme = todoPopupColorScheme(dark, amoled)
            MaterialTheme(colorScheme = colorScheme) {
            val editingId = intent.getLongExtra(TodoWidget.EXTRA_TODO_ID, -1L)
            val existing = remember(editingId) {
                if (editingId >= 0L) TodoStore.load(ctx).firstOrNull { it.id == editingId } else null
            }
            val initialTask = existing?.text.orEmpty()
            // A completed task has no reminder (finishing a task removes it), so it cannot get a new one either.
            val canRemind = existing?.done != true
            val initialPending = remember(editingId) {
                existing?.let { TodoReminders.get(ctx, it) }?.let { PendingTodoReminder(it.date, it.time, it.repeat, it.interval) }
            }
            var task by remember(editingId) { mutableStateOf(TextFieldValue(initialTask, selection = TextRange(initialTask.length))) }
            var pending by remember(editingId) { mutableStateOf(initialPending) }
            val keyboard = LocalSoftwareKeyboardController.current
            val focusRequester = remember { FocusRequester() }
            val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
            val scheduleState = remember { MutableTransitionState(false) }
            // Open keyboard only after the panel has mostly slid in, so the animation isn't janked.
            LaunchedEffect(Unit) {
                delay(180)
                focusRequester.requestFocus()
                keyboard?.show()
            }
            // Finish only once the exit animation has completed.
            LaunchedEffect(visibleState.currentState, visibleState.targetState) {
                if (!visibleState.currentState && !visibleState.targetState) finish()
            }
            fun close() { keyboard?.hide(); visibleState.targetState = false }
            fun openSchedule() { keyboard?.hide(); scheduleState.targetState = true }
            fun closeSchedule() { scheduleState.targetState = false }
            BackHandler { if (scheduleState.targetState) closeSchedule() else close() }
            val scrimAlpha by animateFloatAsState(
                targetValue = if (visibleState.targetState) 0.58f else 0f,
                animationSpec = tween(260, easing = FastOutSlowInEasing),
                label = "scrim"
            )
            fun applyReminder(todoId: Long, title: String) {
                val p = pending
                if (p == null) {
                    if (initialPending != null) TodoReminders.clear(ctx, todoId)
                } else if (p != initialPending) {
                    TodoReminders.set(ctx, todoId, title, p.date, p.time, p.repeat, p.interval)
                    promptExactAlarmIfNeeded()
                }
            }
            fun save() {
                val text = task.text.trim()
                if (editingId >= 0L) {
                    if (text.isEmpty()) TodoStore.delete(ctx, editingId)
                    else {
                        TodoStore.rename(ctx, editingId, text)
                        applyReminder(editingId, text)
                    }
                    close()
                } else if (text.isNotEmpty()) {
                    val newId = TodoStore.add(ctx, text)
                    if (newId >= 0L) applyReminder(newId, text)
                    close()
                }
            }
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(interactionSource = noRipple, indication = null) {
                        if (scheduleState.targetState) closeSchedule() else close()
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it },
                exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it }
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                color = panel,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            ) {
                Column(Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cancel", color = accent, fontSize = 16.sp, modifier = Modifier
                            .clickable { close() }.padding(vertical = 8.dp), fontWeight = FontWeight.Medium)
                        Text(if (editingId >= 0L) "Edit To-Do" else "New To-Do", color = foreground, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Save", color = if (editingId >= 0L || task.text.isNotBlank()) accent else muted,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(enabled = editingId >= 0L || task.text.isNotBlank()) { save() }.padding(vertical = 8.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp)
                            .background(field, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = task,
                            onValueChange = { task = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = TextStyle(color = foreground, fontSize = 17.sp),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            decorationBox = { inner ->
                                if (task.text.isEmpty()) Text("New to-do", color = muted, fontSize = 17.sp)
                                inner()
                            }
                        )
                    }
                    // Bell: opens the Schedule reminder sheet. Once set it shows the time next to the bell.
                    val reminderChip = pending?.let {
                        todoReminderLabel(DateReminder("pending", "", it.date, it.time, it.repeat, it.interval))
                    }
                    if (canRemind) Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp))
                                .clickable { openSchedule() }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_reminder),
                                contentDescription = "Add reminder",
                                tint = if (pending != null) accent else muted,
                                modifier = Modifier.size(24.dp)
                            )
                            if (reminderChip != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(reminderChip, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (pending != null) {
                            Text(
                                "✕", color = muted, fontSize = 16.sp,
                                modifier = Modifier.clip(CircleShape).clickable { pending = null }.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
            }
            // Schedule reminder: slides up over the To-Do sheet with the same animation.
            AnimatedVisibility(
                visibleState = scheduleState,
                enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it },
                exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it }
            ) {
                TodoScheduleSheet(
                    initial = pending,
                    dark = dark,
                    panel = panel,
                    field = field,
                    foreground = foreground,
                    muted = muted,
                    accent = accent,
                    onCancel = { closeSchedule() },
                    onSave = { value ->
                        requestNotificationPermissionIfNeeded()
                        pending = value
                        closeSchedule()
                    }
                )
            }
            }
            }
        }
    }
}

/** Same colours as the app's own theme, so the app's selectors, buttons and menus look identical in the popup. */
private fun todoPopupColorScheme(dark: Boolean, amoled: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    background = if (amoled) Color(0xFF000000) else Color(0xFF121212),
    surface = if (amoled) Color(0xFF000000) else Color(0xFF181818),
    surfaceVariant = if (amoled) Color(0xFF151515) else Color(0xFF242424),
    surfaceContainerLowest = if (amoled) Color(0xFF000000) else Color(0xFF101010),
    surfaceContainerLow = if (amoled) Color(0xFF080808) else Color(0xFF191919),
    surfaceContainer = if (amoled) Color(0xFF101010) else Color(0xFF202020),
    surfaceContainerHigh = if (amoled) Color(0xFF151515) else Color(0xFF292929),
    surfaceContainerHighest = if (amoled) Color(0xFF1C1C1C) else Color(0xFF303030),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = if (amoled) Color(0xFF444444) else Color(0xFF505050),
    outlineVariant = if (amoled) Color(0xFF444444) else Color(0xFF505050),
    secondaryContainer = if (amoled) Color(0xFF2A2A2A) else Color(0xFF383838),
    onSecondaryContainer = Color(0xFFF5F5F5)
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
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFD0D0D0),
    secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Color(0xFF1C1B1F)
)

/**
 * "Schedule reminder" sheet. The date, time and repeat controls are the same ones the app's
 * Date Reminder form uses (day / month / year selector, time button, repeat menu).
 */
@Composable
private fun TodoScheduleSheet(
    initial: PendingTodoReminder?,
    dark: Boolean,
    panel: Color,
    field: Color,
    foreground: Color,
    muted: Color,
    accent: Color,
    onCancel: () -> Unit,
    onSave: (PendingTodoReminder) -> Unit
) {
    val context = LocalContext.current
    // Same starting values as the Date Reminder form: today, at the next whole minute.
    val startTime = remember { defaultReminderTime() }
    val startDate = remember { if (startTime < LocalTime.now()) LocalDate.now().plusDays(1) else LocalDate.now() }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var dateState by remember { mutableStateOf(DateInputState.from(initial?.date ?: startDate)) }
    var time by remember { mutableStateOf(initial?.time ?: startTime) }
    var repeatName by remember { mutableStateOf((initial?.repeat ?: ReminderRepeat.NONE).name) }
    val repeat = ReminderRepeat.fromStored(repeatName)
    var customRepeat by remember { mutableStateOf((initial?.interval ?: 1) > 1) }
    var intervalText by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showRepeatMenu by remember { mutableStateOf(false) }
    var showUnitMenu by remember { mutableStateOf(false) }

    val date = dateState.toDate()
    val intervalValue: Int? =
        if (customRepeat && repeat != ReminderRepeat.NONE) intervalText.toIntOrNull()?.takeIf { it in 1..99 } else 1

    // Same rules as the Date Reminder form.
    fun validationMessage(now: LocalDateTime): String? {
        val currentDate = now.toLocalDate()
        val selected = date ?: return "Please enter a valid reminder date."
        if (intervalValue == null) return "Enter a repeat number from 1 to 99."
        if (repeat != ReminderRepeat.NONE) return null
        if (selected.isBefore(currentDate)) return "Past dates can't be used for reminders. Please choose today or a future date."
        if (selected.isEqual(currentDate) && !time.isAfter(now.toLocalTime())) {
            return "This time has already passed today. Choose a later time or a future date."
        }
        return null
    }
    // nowMillis ticks while the sheet is open, so the warning appears as soon as the chosen time passes.
    val message = validationMessage(
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), java.time.ZoneId.systemDefault())
    )
    val noRipple = remember { MutableInteractionSource() }
    val menuBorder = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(interactionSource = noRipple, indication = null) { },
        color = panel,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .verticalScroll(scrollState)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Cancel", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onCancel() }.padding(vertical = 8.dp))
                Text("Schedule reminder", color = foreground, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("Save", color = if (message == null) accent else muted, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = message == null) {
                        val chosen = date
                        val every = intervalValue
                        if (validationMessage(LocalDateTime.now()) == null && chosen != null && every != null) {
                            onSave(PendingTodoReminder(chosen, time, repeat, every))
                        }
                    }.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(field, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                DateSelector("Reminder date", dateState, { dateState = it }, allowToday = true)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reminder time: ${formattedTime(time)}")
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showRepeatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Repeat: " + when {
                                repeat == ReminderRepeat.NONE -> ReminderRepeat.NONE.menuLabel
                                customRepeat -> "Custom"
                                else -> repeat.menuLabel
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showRepeatMenu,
                        onDismissRequest = { showRepeatMenu = false },
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 0.dp,
                        shadowElevation = 4.dp,
                        border = menuBorder
                    ) {
                        ReminderRepeat.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.menuLabel) },
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                onClick = {
                                    repeatName = option.name
                                    customRepeat = false
                                    intervalText = "1"
                                    showRepeatMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Custom…") },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            onClick = {
                                if (repeat == ReminderRepeat.NONE) repeatName = ReminderRepeat.DAILY.name
                                customRepeat = true
                                showRepeatMenu = false
                            }
                        )
                    }
                }
                if (customRepeat && repeat != ReminderRepeat.NONE) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { value -> intervalText = value.filter { it.isDigit() }.take(2) },
                            label = { Text("Every") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            modifier = Modifier.width(96.dp).onFocusEvent { state ->
                                if (state.isFocused) {
                                    scope.launch {
                                        delay(300)
                                        scrollState.animateScrollTo(scrollState.maxValue)
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { showUnitMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    when (repeat) {
                                        ReminderRepeat.DAILY -> "Days"
                                        ReminderRepeat.WEEKLY -> "Weeks"
                                        ReminderRepeat.MONTHLY -> "Months"
                                        else -> "Years"
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = showUnitMenu,
                                onDismissRequest = { showUnitMenu = false },
                                shape = RoundedCornerShape(14.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 0.dp,
                                shadowElevation = 4.dp,
                                border = menuBorder
                            ) {
                                listOf(
                                    ReminderRepeat.DAILY to "Days",
                                    ReminderRepeat.WEEKLY to "Weeks",
                                    ReminderRepeat.MONTHLY to "Months",
                                    ReminderRepeat.YEARLY to "Years"
                                ).forEach { (unit, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        onClick = {
                                            repeatName = unit.name
                                            showUnitMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                repeatHelpText(repeat, intervalValue ?: 1)?.let { help ->
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showTimePicker) {
        val pickerTheme = if (dark) R.style.Theme_DayCalculator_TimePicker_Dark else R.style.Theme_DayCalculator_TimePicker_Light
        val pickerContext = remember(dark) { ContextThemeWrapper(context, pickerTheme) }
        DisposableEffect(showTimePicker, dark) {
            val dialog = android.app.TimePickerDialog(
                pickerContext,
                { _, hour, minute ->
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
