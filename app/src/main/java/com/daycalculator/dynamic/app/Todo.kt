package com.daycalculator.dynamic.app

import android.app.PendingIntent
import android.appwidget.AppWidgetProvider
import android.widget.RemoteViewsService
import android.widget.RemoteViews
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.launch
import org.json.JSONObject

/* -------------------------------------------------------------------------- */
/*  Data                                                                       */
/* -------------------------------------------------------------------------- */

internal data class TodoItem(
    val id: Long,
    val text: String,
    val done: Boolean,
    val createdAt: Long,
    val completedAt: Long = 0L,
    /** Id of the matching entry in the app's Date Reminders (null when the to-do has no reminder). */
    val reminderId: String? = null
)

/**
 * Single to-do list stored locally (same SharedPreferences + JSON approach as the
 * saved trackers). Every change refreshes any To-do widgets on the home screen.
 */
internal object TodoStore {
    private const val PREFS = "daycalcy_todos"
    private const val KEY = "items"
    // Set once the list has been saved in the user's own (manual) order.
    private const val MANUAL_ORDER = "manual_order"

    /**
     * Returns the tasks in the order they are shown. The stored order is the display order:
     * new tasks go to the top, tasks can be dragged into any order, and completed tasks sit below.
     * Lists saved by earlier versions (no manual order yet) are shown newest first, as before.
     */
    fun load(context: Context): List<TodoItem> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        val parsed = buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isBlank()) continue
                add(TodoItem(o.optLong("id"), text, o.optBoolean("done"), o.optLong("createdAt"), o.optLong("completedAt"), o.optString("reminderId").takeIf { it.isNotBlank() }))
            }
        }
        if (prefs.getBoolean(MANUAL_ORDER, false)) parsed else legacyOrder(parsed)
    }.getOrDefault(emptyList())

    /** Old rule: newest open tasks first; most recently completed first among completed tasks. */
    private fun legacyOrder(items: List<TodoItem>): List<TodoItem> =
        items.filterNot { it.done }.sortedWith(compareByDescending<TodoItem> { it.createdAt }.thenByDescending { it.id }) +
            items.filter { it.done }.sortedWith(compareByDescending<TodoItem> { it.completedAt }.thenByDescending { it.createdAt }.thenByDescending { it.id })

    /** Open tasks (in the saved order) stay above completed tasks. */
    fun displayOrder(items: List<TodoItem>): List<TodoItem> =
        items.filterNot { it.done } + items.filter { it.done }

    private fun write(context: Context, items: List<TodoItem>) {
        val array = JSONArray()
        items.forEach { t ->
            array.put(JSONObject().put("id", t.id).put("text", t.text).put("done", t.done).put("createdAt", t.createdAt).put("completedAt", t.completedAt).put("reminderId", t.reminderId))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .putBoolean(MANUAL_ORDER, true)
            .apply()
        TodoWidget.updateAll(context)
    }

    /** Adds a task at the top of the list and returns its id (or -1 when the text is blank). */
    fun add(context: Context, text: String): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return -1L
        val now = System.currentTimeMillis()
        val existing = load(context)
        // Keep IDs unique even if two tasks are added within the same millisecond.
        val id = maxOf(now, (existing.maxOfOrNull { it.id } ?: 0L) + 1L)
        // New tasks go to the top of the list.
        write(context, listOf(TodoItem(id, clean, false, now)) + existing)
        return id
    }

    /** Called when a one-time reminder is marked Done from its notification: the to-do is finished too. */
    fun completeForReminder(context: Context, reminderId: String) {
        val items = load(context)
        val target = items.firstOrNull { it.reminderId == reminderId && !it.done } ?: return
        val rest = items.filterNot { it.id == target.id }
        val updated = target.copy(done = true, completedAt = System.currentTimeMillis(), reminderId = null)
        write(context, rest.filterNot { it.done } + updated + rest.filter { it.done })
    }

    fun setReminderId(context: Context, id: Long, reminderId: String?) {
        write(context, load(context).map { if (it.id == id) it.copy(reminderId = reminderId) else it })
    }

    fun toggle(context: Context, id: Long) {
        val items = load(context)
        val target = items.firstOrNull { it.id == id } ?: return
        // Completing a task also removes its reminder, so it does not fire for finished work.
        if (!target.done) target.reminderId?.let { TodoReminders.remove(context, it) }
        val updated = if (target.done) target.copy(done = false, completedAt = 0L)
        else target.copy(done = true, completedAt = System.currentTimeMillis(), reminderId = null)
        val rest = items.filterNot { it.id == id }
        val open = rest.filterNot { it.done }
        val done = rest.filter { it.done }
        // A completed task goes to the top of the completed tasks; a reopened one goes back to the top of the open tasks.
        write(context, if (updated.done) open + updated + done else listOf(updated) + open + done)
    }

    /** Saves a new order for the open tasks. Completed tasks keep their order below them. */
    fun reorderOpen(context: Context, orderedIds: List<Long>) {
        val items = load(context)
        val byId = items.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }.filterNot { it.done }
        val placed = reordered.map { it.id }.toSet()
        // A task added elsewhere (for example from the widget) while dragging stays at the top.
        val extra = items.filter { !it.done && it.id !in placed }
        write(context, extra + reordered + items.filter { it.done })
    }

    fun rename(context: Context, id: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val items = load(context)
        items.firstOrNull { it.id == id }?.reminderId?.let { TodoReminders.retitle(context, it, clean) }
        write(context, items.map { if (it.id == id) it.copy(text = clean) else it })
    }

    fun delete(context: Context, id: Long) {
        val items = load(context)
        items.filter { it.id == id }.forEach { t -> t.reminderId?.let { TodoReminders.remove(context, it) } }
        write(context, items.filterNot { it.id == id })
    }

    fun clearCompleted(context: Context) {
        val items = load(context)
        items.filter { it.done }.forEach { t -> t.reminderId?.let { TodoReminders.remove(context, it) } }
        write(context, items.filterNot { it.done })
    }
}

/* -------------------------------------------------------------------------- */
/*  Reminders for to-dos (built on the app's existing Date Reminders)          */
/* -------------------------------------------------------------------------- */

internal object TodoReminders {
    /** The reminder attached to [item], or null when it has none (or it was deleted from the Reminders screen). */
    fun get(context: Context, item: TodoItem): DateReminder? {
        val id = item.reminderId ?: return null
        return ReminderStore.load(context).firstOrNull { it.id == id }
    }

    /** Creates (or replaces) the reminder of a to-do and schedules its alarm. */
    fun set(context: Context, todoId: Long, title: String, date: LocalDate, time: LocalTime, repeat: ReminderRepeat, interval: Int = 1) {
        val item = TodoStore.load(context).firstOrNull { it.id == todoId } ?: return
        item.reminderId?.let { remove(context, it) }
        val reminder = DateReminder(newReminderId(), title.trim(), date, time, repeat, interval.coerceIn(1, 99))
        val all = (ReminderStore.load(context) + reminder).sortedWith(compareBy<DateReminder> { it.date }.thenBy { it.time })
        ReminderStore.save(context, all)
        ReminderScheduler.schedule(context, reminder)
        TodoStore.setReminderId(context, todoId, reminder.id)
        DateReminderWidget.updateAll(context)
    }

    /** Removes the reminder of a to-do, if it has one. */
    fun clear(context: Context, todoId: Long) {
        val item = TodoStore.load(context).firstOrNull { it.id == todoId } ?: return
        val id = item.reminderId ?: return
        remove(context, id)
        TodoStore.setReminderId(context, todoId, null)
    }

    /** Cancels the alarm and deletes the reminder entry. */
    fun remove(context: Context, reminderId: String) {
        ReminderScheduler.cancel(context, reminderId)
        ReminderStore.save(context, ReminderStore.load(context).filterNot { it.id == reminderId })
        DateReminderWidget.updateAll(context)
    }

    /** Keeps the notification text in sync when a to-do is renamed. */
    fun retitle(context: Context, reminderId: String, title: String) {
        val all = ReminderStore.load(context)
        val old = all.firstOrNull { it.id == reminderId } ?: return
        val updated = old.copy(title = title)
        ReminderStore.save(context, all.map { if (it.id == reminderId) updated else it })
        ReminderScheduler.schedule(context, updated)
        DateReminderWidget.updateAll(context)
    }
}

/** Short text such as "Today, 10:45 am" or "9 Nov, 8:00 am · Every week" (repeating ones show the next occurrence). */
internal fun todoReminderLabel(reminder: DateReminder, compact: Boolean = false): String {
    val now = LocalDateTime.now()
    val target = if (reminder.repeat == ReminderRepeat.NONE) LocalDateTime.of(reminder.date, reminder.time) else reminderTarget(reminder, now)
    val today = now.toLocalDate()
    val day = target.toLocalDate()
    val dayText = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        // The compact (widget) label never shows the year, so it fits on one small line.
        else -> day.format(DateTimeFormatter.ofPattern(if (compact || day.year == today.year) "d MMM" else "d MMM yyyy", Locale.getDefault()))
    }
    val timeText = target.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    // The compact (widget) label drops the comma and the space before am/pm: "Tomorrow 7:25pm".
    val base = if (compact) "$dayText ${timeText.replace(" ", "").replace("\u202f", "")}" else "$dayText, $timeText"
    return when {
        reminder.repeat == ReminderRepeat.NONE -> base
        compact -> "$base ↻"
        else -> "$base · ${reminderRepeatText(reminder.repeat, reminder.interval)}"
    }
}

internal fun todoReminderOverdue(reminder: DateReminder): Boolean =
    reminder.repeat == ReminderRepeat.NONE && LocalDateTime.of(reminder.date, reminder.time).isBefore(LocalDateTime.now())

/* -------------------------------------------------------------------------- */
/*  Home-screen widget                                                         */
/* -------------------------------------------------------------------------- */

/**
 * Content of the resizable To-do List widget. Tapping a task checks it off or opens it for editing;
 * tapping the header opens the To-do screen and "+" opens the quick-add popup.
 */
internal object TodoWidget {
    const val ACTION_TOGGLE = "com.daycalculator.dynamic.app.TODO_WIDGET_TOGGLE"
    const val ACTION_EDIT = "com.daycalculator.dynamic.app.TODO_WIDGET_EDIT"
    const val ACTION_ADD = "com.daycalculator.dynamic.app.TODO_WIDGET_ADD"
    const val EXTRA_TODO_ID = "daycalcy_todo_id"
    const val EXTRA_EDIT_TEXT = "daycalcy_todo_edit_text"



    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, ResizableTodoWidget::class.java))
            .forEach { update(context, manager, it) }
    }

    /** Opens the To-do popup (new task, or edit when [todoId] is given) from the widget or from the app. */
    fun openEditor(context: Context, todoId: Long?) {
        val launch = Intent(context, TodoQuickAddActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            if (todoId != null) {
                putExtra(EXTRA_TODO_ID, todoId)
                data = Uri.parse("daycalcy-todo-edit://task/$todoId")
            }
        }
        context.startActivity(launch)
    }

    /** Called by the widget provider when a task row on the widget is tapped. */
    fun handleToggle(context: Context, intent: Intent) {
        if (intent.action == ACTION_ADD) {
            // Launched from this receiver (same path as row-edit) so + gets the same smooth popup animation
            // instead of the launcher's own zoom-from-widget transition.
            val launch = Intent(context, TodoQuickAddActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                data = Uri.parse("daycalcy-todo-quick-add://widget/${intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)}")
            }
            context.startActivity(launch)
            return
        }
        val id = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        if (id >= 0L) {
            if (intent.action == ACTION_EDIT) {
                val launch = Intent(context, TodoQuickAddActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(EXTRA_TODO_ID, id)
                    data = Uri.parse("daycalcy-todo-edit://task/$id")
                }
                context.startActivity(launch)
            } else if (intent.action == ACTION_TOGGLE) TodoStore.toggle(context, id)
        }
    }

    private fun isDark(context: Context): Boolean =
        when (context.getSharedPreferences("day_calculator_settings", Context.MODE_PRIVATE).getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> false
            "DARK", "AMOLED" -> true
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        // Receiver that gets the + and row taps for this widget.
        val receiverClass = ResizableTodoWidget::class.java
        val views = RemoteViews(context.packageName, R.layout.widget_todo_resizable)
        val dark = isDark(context)
        val fg = if (dark) AndroidColor.rgb(245, 245, 245) else AndroidColor.rgb(17, 17, 17)
        views.setInt(R.id.todo_widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background_light)
        views.setTextColor(R.id.todo_title, fg)
        val all = TodoStore.displayOrder(TodoStore.load(context))
        views.setTextViewText(R.id.todo_title, "${all.size} TO-DOS")
        views.setInt(R.id.todo_add, "setBackgroundResource", if (dark) R.drawable.todo_add_button else R.drawable.todo_add_button_light)
        views.setTextColor(R.id.todo_add, fg)
        // Header count is the deliberate navigation target; + remains quick-add only.
        val headerIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_TODOS, true)
            data = Uri.parse("daycalcy-todo-home://widget/$widgetId")
        }
        val headerPending = PendingIntent.getActivity(context, widgetId + 9600, headerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.todo_title, headerPending)

        val serviceIntent = Intent(context, TodoWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("daycalcy-todo-list://widget/$widgetId")
        }
        views.setRemoteAdapter(R.id.todo_list, serviceIntent)
        views.setEmptyView(R.id.todo_list, R.id.todo_empty)
        val template = Intent(context, receiverClass).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val clickTemplate = PendingIntent.getBroadcast(context, widgetId + 8700, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        views.setPendingIntentTemplate(R.id.todo_list, clickTemplate)
        val addIntent = Intent(context, receiverClass).apply {
            action = ACTION_ADD
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("daycalcy-todo-add://widget/$widgetId")
        }
        val openPending = PendingIntent.getBroadcast(context, widgetId + 9500, addIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.todo_add, openPending)
        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.todo_list)
    }

}

class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodoRemoteViewsFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private class TodoRemoteViewsFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<TodoItem> = emptyList()
    override fun onCreate() {}
    private var reminders: Map<String, DateReminder> = emptyMap()
    // True when the resizable widget is wide enough to show the full details that are shortened on small sizes.
    private var roomy = false
    override fun onDataSetChanged() {
        items = TodoStore.displayOrder(TodoStore.load(context))
        reminders = ReminderStore.load(context).associateBy { it.id }
        roomy = isRoomy()
    }

    private fun isRoomy(): Boolean {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(widgetId)?.provider?.className != ResizableTodoWidget::class.java.name) return false
        val widthDp = manager.getAppWidgetOptions(widgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        return widthDp >= 240
    }
    override fun onDestroy() { items = emptyList(); reminders = emptyMap() }
    override fun getCount() = items.size
    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in items.indices) return null
        val item = items[position]
        val dark = when (context.getSharedPreferences("day_calculator_settings", Context.MODE_PRIVATE).getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> false
            "DARK", "AMOLED" -> true
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val fg = if (dark) AndroidColor.rgb(245,245,245) else AndroidColor.rgb(25,25,25)
        val secondary = if (dark) AndroidColor.rgb(170,170,170) else AndroidColor.rgb(100,100,100)
        val row = RemoteViews(context.packageName, R.layout.widget_todo_row)
        row.setInt(R.id.todo_item_root, "setBackgroundResource", if (dark) R.drawable.todo_task_background else R.drawable.todo_task_background_light)
        val displayText = if (item.done) SpannableString(item.text).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else item.text
        row.setTextViewText(R.id.todo_item_text, displayText)
        row.setTextColor(R.id.todo_item_text, if (item.done) secondary else fg)
        if (roomy) {
            // Plenty of room: bigger text and longer task names are no longer cut off after two lines.
            row.setTextViewTextSize(R.id.todo_item_text, android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            row.setInt(R.id.todo_item_text, "setMaxLines", 4)
        }
        // Use pre-coloured resources so RemoteViews never needs a no-argument
        // clearColorFilter call (which RemoteViews.setInt cannot represent).
        row.setImageViewResource(
            R.id.todo_item_check,
            when {
                item.done -> R.drawable.ic_todo_done
                dark -> R.drawable.ic_todo_circle
                else -> R.drawable.ic_todo_circle_light
            }
        )
        val toggle = Intent().setAction(TodoWidget.ACTION_TOGGLE).putExtra(TodoWidget.EXTRA_TODO_ID, item.id)
        val edit = Intent().setAction(TodoWidget.ACTION_EDIT).putExtra(TodoWidget.EXTRA_TODO_ID, item.id)
        row.setOnClickFillInIntent(R.id.todo_item_check, toggle)
        row.setOnClickFillInIntent(R.id.todo_item_text, edit)
        // Small bell + time under the task when it has a reminder.
        val reminder = item.reminderId?.let { reminders[it] }
        if (reminder != null && !item.done) {
            val overdue = todoReminderOverdue(reminder)
            val reminderColor = if (overdue) AndroidColor.rgb(229, 115, 115) else secondary
            row.setViewVisibility(R.id.todo_item_reminder_row, android.view.View.VISIBLE)
            // Full text ("Tomorrow, 7:25 pm · Every week", with the year) when there is room for it.
            row.setTextViewText(R.id.todo_item_reminder, todoReminderLabel(reminder, compact = !roomy))
            if (roomy) {
                row.setTextViewTextSize(R.id.todo_item_reminder, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                row.setInt(R.id.todo_item_reminder, "setMaxLines", 2)
            }
            row.setTextColor(R.id.todo_item_reminder, reminderColor)
            row.setInt(R.id.todo_item_reminder_icon, "setColorFilter", reminderColor)
            row.setOnClickFillInIntent(R.id.todo_item_reminder_row, edit)
        } else {
            row.setViewVisibility(R.id.todo_item_reminder_row, android.view.View.GONE)
        }
        return row
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = items.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds() = true
}

/* -------------------------------------------------------------------------- */
/*  In-app screen                                                              */
/* -------------------------------------------------------------------------- */

@Composable
internal fun TodoScreen(scrollState: ScrollState? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(TodoStore.load(context)) }
    var newText by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    // Task waiting for the user to confirm its deletion.
    var deleteCandidate by remember { mutableStateOf<TodoItem?>(null) }

    var reminderVersion by remember { mutableIntStateOf(0) }
    fun refresh() { items = TodoStore.load(context); reminderVersion++ }

    // Reload when the app returns to the foreground so ticks/adds made from the widget show up.
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    fun submit() {
        if (newText.isNotBlank()) {
            TodoStore.add(context, newText)
            newText = ""
            refresh()
        }
    }

    val orderedItems = TodoStore.displayOrder(items)
    val open = orderedItems.filterNot { it.done }
    val done = orderedItems.filter { it.done }
    val reminderById = remember(items, reminderVersion) { ReminderStore.load(context).associateBy { it.id } }

    // ---- Drag to reorder (press and hold a task, then drag) ----
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val openIds = open.map { it.id }
    val currentOpenIds by rememberUpdatedState(openIds)
    val rowHeights = remember { mutableStateMapOf<Long, Int>() }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var fingerWindowY by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var lastDragEnd by remember { mutableLongStateOf(0L) }

    fun clickAllowed(): Boolean = draggingId == null && System.currentTimeMillis() - lastDragEnd > 350L

    // Which position the dragged row currently hovers over.
    fun slotFor(ids: List<Long>, from: Int, offset: Float): Int {
        if (from !in ids.indices) return from
        val heights = ids.map { (rowHeights[it] ?: 0).toFloat() }
        val center = heights.take(from).sum() + heights[from] / 2f + offset
        var bottom = 0f
        for (i in ids.indices) {
            bottom += heights[i]
            if (center < bottom) return i
        }
        return ids.lastIndex
    }

    // Distance the dragged row has to travel to sit exactly in its new slot.
    fun settleOffset(ids: List<Long>, from: Int, to: Int): Float {
        fun h(i: Int): Float = (rowHeights[ids[i]] ?: 0).toFloat()
        return when {
            to > from -> ((from + 1)..to).sumOf { h(it).toDouble() }.toFloat()
            to < from -> -(to until from).sumOf { h(it).toDouble() }.toFloat()
            else -> 0f
        }
    }

    fun moveOpen(from: Int, to: Int) {
        val ids = currentOpenIds.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        TodoStore.reorderOpen(context, ids)
        refresh()
    }

    fun endDrag(id: Long) {
        if (draggingId != id || settling) return
        val ids = currentOpenIds
        val from = ids.indexOf(id)
        if (from < 0) {
            draggingId = null
            dragOffsetPx = 0f
            return
        }
        val to = slotFor(ids, from, dragOffsetPx)
        val target = settleOffset(ids, from, to)
        val start = dragOffsetPx
        settling = true
        scope.launch {
            animate(start, target, animationSpec = tween(140)) { value, _ -> dragOffsetPx = value }
            if (to != from) moveOpen(from, to)
            dragOffsetPx = 0f
            lastDragEnd = System.currentTimeMillis()
            draggingId = null
            settling = false
        }
    }

    // Scroll the page while the dragged row is held near the top or bottom edge of the screen.
    LaunchedEffect(draggingId, settling) {
        val state = scrollState
        if (draggingId == null || settling || state == null) return@LaunchedEffect
        val topEdge = with(density) { 200.dp.toPx() }
        val bottomEdge = view.height - with(density) { 110.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val y = fingerWindowY
            val delta = when {
                y < topEdge -> -min(28f, (topEdge - y) / 6f)
                y > bottomEdge -> min(28f, (y - bottomEdge) / 6f)
                else -> 0f
            }
            if (delta != 0f) {
                // Keep the dragged row under the finger while the content moves.
                dragOffsetPx += state.scrollBy(delta)
            }
        }
    }

    val fromIndex = draggingId?.let { openIds.indexOf(it) } ?: -1
    val toIndex = if (fromIndex >= 0) slotFor(openIds, fromIndex, dragOffsetPx) else -1
    val draggedHeight = if (fromIndex >= 0) (rowHeights[openIds[fromIndex]] ?: 0).toFloat() else 0f

    Text("To-do List", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "Check tasks off here or from the home-screen widget. Press and hold a task to reorder it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newText,
            onValueChange = { newText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Add a task") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        Button(onClick = { submit() }, enabled = newText.isNotBlank()) { Text("+ Add") }
    }
    Spacer(Modifier.height(10.dp))

    if (items.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✓", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("No tasks yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Add a task above. You can also put a To-do widget on your home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        open.forEachIndexed { index, item ->
            key(item.id) {
                val isDragged = item.id == draggingId
                val shift = when {
                    fromIndex < 0 || isDragged -> 0f
                    fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> -draggedHeight
                    toIndex < fromIndex && index in toIndex until fromIndex -> draggedHeight
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(
                    targetValue = shift,
                    animationSpec = if (draggingId != null) tween(150) else snap(),
                    label = "todoRowShift"
                )
                val rowTop = remember { floatArrayOf(0f) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .onGloballyPositioned { rowTop[0] = it.positionInWindow().y }
                        .onSizeChanged { rowHeights[item.id] = it.height }
                        .pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    if (draggingId == null && !settling) {
                                        draggingId = item.id
                                        dragOffsetPx = 0f
                                        fingerWindowY = rowTop[0] + offset.y
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, amount ->
                                    if (draggingId == item.id && !settling) {
                                        change.consume()
                                        dragOffsetPx += amount.y
                                        fingerWindowY += amount.y
                                    }
                                },
                                onDragEnd = { endDrag(item.id) },
                                onDragCancel = { endDrag(item.id) }
                            )
                        }
                        .graphicsLayer {
                            translationY = if (isDragged) dragOffsetPx else animatedShift
                            val scale = if (isDragged) 1.03f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("Move up") { moveOpen(index, index - 1); true })
                                if (index < open.lastIndex) add(CustomAccessibilityAction("Move down") { moveOpen(index, index + 1); true })
                            }
                        }
                ) {
                    TodoRow(
                        item = item,
                        dragMode = draggingId != null,
                        lifted = isDragged,
                        reminder = item.reminderId?.let { reminderById[it] },
                        onToggle = { if (clickAllowed()) { TodoStore.toggle(context, item.id); refresh() } },
                        onEdit = { if (clickAllowed()) TodoWidget.openEditor(context, item.id) },
                        onDelete = { if (clickAllowed()) deleteCandidate = item }
                    )
                }
            }
        }
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Completed (${done.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
            done.forEach { item ->
                TodoRow(
                    item = item,
                    onToggle = { if (clickAllowed()) { TodoStore.toggle(context, item.id); refresh() } },
                    onEdit = { if (clickAllowed()) TodoWidget.openEditor(context, item.id) },
                    onDelete = { if (clickAllowed()) deleteCandidate = item }
                )
            }
        }
    }

    deleteCandidate?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete task?") },
            text = {
                Text(
                    "\"${task.text}\" will be permanently deleted." +
                        if (task.reminderId != null) " Its reminder will be removed too." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TodoStore.delete(context, task.id)
                    deleteCandidate = null
                    refresh()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear completed tasks?") },
            text = { Text("This permanently deletes ${done.size} completed task${if (done.size == 1) "" else "s"}.") },
            confirmButton = {
                TextButton(onClick = {
                    TodoStore.clearCompleted(context)
                    confirmClear = false
                    refresh()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

}

@Composable
private fun TodoRow(
    item: TodoItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragMode: Boolean = false,
    lifted: Boolean = false,
    reminder: DateReminder? = null
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (lifted) 10.dp else 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dragMode && !item.done) {
                // While reordering, open tasks show a drag handle instead of the check circle.
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text("≡", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                }
            } else IconButton(
                onClick = onToggle,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = if (item.done) "Mark as not done" else "Mark as done"
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .then(
                            if (item.done) Modifier.background(Color(0xFF69D38A))
                            else Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.done) {
                        Text("✓", color = Color(0xFF111111), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 14.dp, horizontal = 4.dp)) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (reminder != null && !item.done) {
                    val reminderColor = if (todoReminderOverdue(reminder)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_reminder), contentDescription = "Reminder", tint = reminderColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(todoReminderLabel(reminder), style = MaterialTheme.typography.bodySmall, color = reminderColor)
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp).semantics { contentDescription = "Delete task" }) {
                Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
