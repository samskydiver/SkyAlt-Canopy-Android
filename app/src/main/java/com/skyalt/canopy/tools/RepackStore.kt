package com.skyalt.canopy.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.skyalt.canopy.MainActivity
import com.skyalt.canopy.R
import com.skyalt.canopy.app.AppServices
import com.skyalt.canopy.ui.Strings
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A rig whose reserve repack date is tracked. Port of RigRecord.swift. */
data class Rig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val reserveModel: String = "",
    val reserveSerialNumber: String = "",
    val lastRepackDate: LocalDate,
    val repackIntervalDays: Int = 180,
    val advanceReminderDays: Int = 30,
) {
    val dueDate: LocalDate get() = RepackSchedule.dueDate(lastRepackDate, repackIntervalDays)
    val reminderDate: LocalDate get() = RepackSchedule.reminderDate(dueDate, advanceReminderDays)

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("reserveModel", reserveModel); put("reserveSerialNumber", reserveSerialNumber)
        put("lastRepackDate", lastRepackDate.toString()); put("repackIntervalDays", repackIntervalDays); put("advanceReminderDays", advanceReminderDays)
    }

    companion object {
        fun fromJson(o: JSONObject) = Rig(
            o.getString("id"), o.getString("name"), o.optString("reserveModel"), o.optString("reserveSerialNumber"),
            LocalDate.parse(o.getString("lastRepackDate")), o.optInt("repackIntervalDays", 180), o.optInt("advanceReminderDays", 30),
        )
    }
}

/** Calendar-day arithmetic shared by the record and the editor preview. */
object RepackSchedule {
    enum class Status { CURRENT, DUE_SOON, OVERDUE }

    fun dueDate(last: LocalDate, intervalDays: Int): LocalDate = last.plusDays(intervalDays.toLong())
    fun reminderDate(due: LocalDate, advanceDays: Int): LocalDate = due.minusDays(advanceDays.toLong())

    fun status(due: LocalDate, reminder: LocalDate, today: LocalDate = LocalDate.now()): Status = when {
        today.isAfter(due) -> Status.OVERDUE
        !today.isBefore(reminder) -> Status.DUE_SOON
        else -> Status.CURRENT
    }

    /** Whole days from today to the due date (negative once overdue). */
    fun daysUntilDue(due: LocalDate, today: LocalDate = LocalDate.now()): Long = ChronoUnit.DAYS.between(today, due)
}

/** The rigs, kept as JSON in the app's preferences, observable by Compose. */
class RepackStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("altix-repack", Context.MODE_PRIVATE)
    val rigs = mutableStateListOf<Rig>().apply { addAll(load()) }

    private fun load(): List<Rig> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { Rig.fromJson(array.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist() {
        prefs.edit().putString(KEY, JSONArray(rigs.map { it.toJson() }).toString()).apply()
    }

    fun save(rig: Rig) {
        val index = rigs.indexOfFirst { it.id == rig.id }
        if (index >= 0) rigs[index] = rig else rigs.add(rig)
        persist()
        RepackReminders.schedule(context, rig)
    }

    fun delete(rig: Rig) {
        rigs.removeAll { it.id == rig.id }
        persist()
        RepackReminders.cancel(context, rig.id)
    }

    private companion object {
        const val KEY = "rigs"
    }
}

/** The 09:00 local reminders for a rig's advance and due dates, through WorkManager. Port of RepackNotificationScheduler.swift. */
object RepackReminders {
    const val CHANNEL = "repack"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun schedule(context: Context, rig: Rig) {
        cancel(context, rig.id)
        val strings = currentStrings(context)
        val now = LocalDateTime.now()
        if (rig.advanceReminderDays > 0) {
            enqueue(
                context, "repack.${rig.id}.advance", rig.reminderDate.atTime(9, 0), now,
                strings("Reserve repack due soon"),
                strings.format("%@ is due for a reserve repack in %lld days.", rig.name, rig.advanceReminderDays),
            )
        }
        enqueue(
            context, "repack.${rig.id}.due", rig.dueDate.atTime(LocalTime.of(9, 0)), now,
            strings("Reserve repack due"),
            strings.format("%@ has reached its %lld-day reserve repack date.", rig.name, rig.repackIntervalDays),
        )
    }

    fun cancel(context: Context, id: String) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("repack.$id.advance")
        wm.cancelUniqueWork("repack.$id.due")
    }

    private fun enqueue(context: Context, name: String, at: LocalDateTime, now: LocalDateTime, title: String, body: String) {
        if (!at.isAfter(now)) return
        val delay = ChronoUnit.MILLIS.between(now.atZone(ZoneId.systemDefault()), at.atZone(ZoneId.systemDefault()))
        val request = OneTimeWorkRequestBuilder<RepackReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("title" to title, "body" to body, "id" to name.hashCode()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    private fun currentStrings(context: Context): Strings {
        val language = AppServices.language(context).value
        return Strings(language.isChinese(context.resources.configuration.locales[0]))
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val name = currentStrings(context)("Repack Reminders")
            manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}

class RepackReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        RepackReminders.ensureChannel(context)
        if (!RepackReminders.canNotify(context)) return Result.success()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, RepackReminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(inputData.getString("title"))
            .setContentText(inputData.getString("body"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(inputData.getString("body")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(inputData.getInt("id", 1), notification)
        } catch (e: SecurityException) {
            // Permission withdrawn since the check.
        }
        return Result.success()
    }
}
