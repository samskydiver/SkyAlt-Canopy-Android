package com.skyalt.canopy.tools

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.Strings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Rigs sorted by the next reserve repack due date. Port of RepackReminderView.swift. */
@Composable
fun RepackScreen(store: RepackStore, onBack: () -> Unit) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf<Rig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Rig?>(null) }

    if (adding || editing != null) {
        RigEditor(editing, store, onClose = { adding = false; editing = null })
        return
    }

    ToolScaffold(s("Repack Reminders"), onBack, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, s("Add Rig")) }
    }) { padding ->
        if (store.rigs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Filled.EventRepeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Text(s("No Rigs"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    FootnoteText(s("Add a rig to track its reserve repack due date."))
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { adding = true }) { Text(s("Add Rig")) }
                }
            }
        } else {
            FormColumn(padding) {
                FormSection(footer = s("Dates are calculated in calendar days. Notification permission is requested when a reminder is saved.")) {
                    store.rigs.sortedBy { it.dueDate }.forEachIndexed { index, rig ->
                        if (index > 0) FormDivider()
                        RigStatusRow(rig, s, onClick = { editing = rig }, onDelete = { confirmDelete = rig })
                    }
                }
                FormSection(s("Rule")) {
                    FootnoteText(s("The default is 180 days for qualifying synthetic reserves. Each rig can use a different interval for its material, jurisdiction, or manufacturer requirements."))
                }
            }
        }
    }

    confirmDelete?.let { rig ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(rig.name) },
            confirmButton = { TextButton(onClick = { store.delete(rig); confirmDelete = null }) { Text(s("Delete"), color = AltixRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(s("Cancel")) } },
        )
    }
}

fun formatDate(date: LocalDate, s: Strings): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(if (s.chinese) Locale.SIMPLIFIED_CHINESE else Locale.US))

@Composable
private fun RigStatusRow(rig: Rig, s: Strings, onClick: () -> Unit, onDelete: () -> Unit) {
    val status = RepackSchedule.status(rig.dueDate, rig.reminderDate)
    val color = when (status) {
        RepackSchedule.Status.CURRENT -> AltixGreen
        RepackSchedule.Status.DUE_SOON -> AltixOrange
        RepackSchedule.Status.OVERDUE -> AltixRed
    }
    val days = abs(RepackSchedule.daysUntilDue(rig.dueDate))
    val statusText = when (status) {
        RepackSchedule.Status.CURRENT -> if (days == 1L) s("Current · 1 day left") else s.format("Current · %lld days left", days)
        RepackSchedule.Status.DUE_SOON -> if (days == 1L) s("Due Soon · 1 day left") else s.format("Due Soon · %lld days left", days)
        RepackSchedule.Status.OVERDUE -> if (days == 1L) s("Overdue · 1 day over") else s.format("Overdue · %lld days over", days)
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rig.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(statusText, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, s("Delete"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        LabeledRow(s("Last repack"), formatDate(rig.lastRepackDate, s))
        LabeledRow(s("Due"), formatDate(rig.dueDate, s), valueColor = color)
        if (rig.reserveModel.isNotEmpty()) FootnoteText(rig.reserveModel)
    }
}

/** Add or edit a rig, then (re)schedule its reminders on save. Port of RigEditorView.swift. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RigEditor(record: Rig?, store: RepackStore, onClose: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(record?.name ?: "") }
    var model by rememberSaveable { mutableStateOf(record?.reserveModel ?: "") }
    var serial by rememberSaveable { mutableStateOf(record?.reserveSerialNumber ?: "") }
    var last by rememberSaveable { mutableStateOf((record?.lastRepackDate ?: LocalDate.now()).toString()) }
    var interval by rememberSaveable { mutableIntStateOf(record?.repackIntervalDays ?: 180) }
    var advance by rememberSaveable { mutableIntStateOf(record?.advanceReminderDays ?: 30) }
    var picking by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    val lastDate = LocalDate.parse(last)
    val due = RepackSchedule.dueDate(lastDate, interval)
    val reminder = RepackSchedule.reminderDate(due, advance)

    fun store() {
        store.save(
            Rig(
                id = record?.id ?: java.util.UUID.randomUUID().toString(),
                name = name.trim(), reserveModel = model.trim(), reserveSerialNumber = serial.trim(),
                lastRepackDate = lastDate, repackIntervalDays = interval,
                advanceReminderDays = minOf(advance, interval - 1),
            ),
        )
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store()
        if (granted) onClose() else warning = s("The rig was saved, but notification permission is disabled. You can enable it in iPhone Settings.").replace("iPhone", "Android")
    }

    ToolScaffold(if (record == null) s("Add Rig") else s("Edit Rig"), onClose, actions = {
        TextButton(enabled = name.isNotBlank(), onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !RepackReminders.canNotify(context)) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                store()
                onClose()
            }
        }) { Text(s("Save"), fontWeight = FontWeight.SemiBold) }
    }) { padding ->
        FormColumn(padding) {
            FormSection(s("Rig")) {
                OutlinedTextField(name, { name = it }, label = { Text(s("Rig name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text(s("Reserve model (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(serial, { serial = it }, label = { Text(s("Reserve serial (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            FormSection(s("Repack")) {
                Row(Modifier.fillMaxWidth().clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(s("Last repack"), modifier = Modifier.weight(1f))
                    Text(formatDate(lastDate, s), color = MaterialTheme.colorScheme.primary)
                }
                FormDivider()
                StepperRow(
                    s("Interval"), s.format("%lld days", interval),
                    onMinus = { interval = (interval - 1).coerceAtLeast(30); advance = advance.coerceAtMost(interval - 1) },
                    onPlus = { interval = (interval + 1).coerceAtMost(365) },
                    minusEnabled = interval > 30, plusEnabled = interval < 365,
                )
                FormDivider()
                StepperRow(
                    s("Advance reminder"), s.format("%lld days", advance),
                    onMinus = { advance = (advance - 1).coerceAtLeast(0) },
                    onPlus = { advance = (advance + 1).coerceAtMost(interval - 1) },
                    minusEnabled = advance > 0, plusEnabled = advance < interval - 1,
                )
            }
            FormSection(s("Calculated")) {
                LabeledRow(s("Due date"), formatDate(due, s), bold = true)
                LabeledRow(s("Reminder date"), formatDate(reminder, s))
            }
        }
    }

    if (picking) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = lastDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { last = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    picking = false
                }) { Text(s("Done")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(s("Cancel")) } },
        ) { DatePicker(state) }
    }

    warning?.let { text ->
        AlertDialog(
            onDismissRequest = { warning = null; onClose() },
            title = { Text(s("Saved Without Notifications")) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { warning = null; onClose() }) { Text(s("Done")) } },
        )
    }
}
