package com.daycalculator.dynamic.app

import android.app.backup.BackupAgentHelper

/**
 * Alarms are never included in Android backups, so after a restore the reminder
 * list comes back but nothing is scheduled. Rebuild the alarms as soon as the
 * restore finishes, without waiting for the user to open the app.
 */
internal class ReminderBackupAgent : BackupAgentHelper() {
    override fun onRestoreFinished() {
        super.onRestoreFinished()
        runCatching {
            ReminderScheduler.rescheduleAll(applicationContext)
            ReminderScheduler.scheduleYearProgressNotifications(applicationContext)
            DateReminderWidget.updateAll(applicationContext)
        }
    }
}
