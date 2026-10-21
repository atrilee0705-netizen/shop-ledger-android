package cn.shopledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.*
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DailyScreen(vm: LedgerViewModel, openManagement: (String) -> Unit, openDetail: (String, LocalDate) -> Unit) {
    val date by vm.date.collectAsStateWithLifecycle()
    val employees by vm.employees.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val totals by vm.dailyTotal.collectAsStateWithLifecycle()
    val employeeTotals by vm.dailyEmployees.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val settlements by vm.settlements.collectAsStateWithLifecycle()
    val settlementItems by vm.items.collectAsStateWithLifecycle()
    var calendar by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var correctionConfirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val active = employees.filterNot { it.isArchived }
    val activeTasks = tasks.filterNot { it.isArchived }
    val cutoff = settlements.firstOrNull()?.periodEnd
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("小店账本", "每天一笔清楚账 · 北京时间")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { if (date > LocalDate.of(1900,1,1)) vm.selectDate(date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一天") }
            TextButton(onClick = { calendar = true }) { Text(date.format(Rules.dateFormat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            IconButton(onClick = { if (date < LocalDate.of(2199,12,31)) vm.selectDate(date.plusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一天") }
        }
        LedgerCard {
            Row(Modifier.fillMaxWidth()) { Amount("全员工资（含停用）", totals.wage, Modifier.weight(1f)); Amount("店铺营业额", totals.revenue, Modifier.weight(1f)) }
            if (cutoff != null && Rules.start(date) <= cutoff) Text("这天已有工资单。补记会同步修正原工资单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (date > Rules.today()) Text("这是未来日期，预记的工作不会提前结账。", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("在职员工  ${active.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { vm.selectDate(Rules.today()) }) { Text("今天") }
            TextButton(onClick = { history = true }) { Text("历史员工") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (active.isEmpty()) item { LedgerCard { Text("先添加员工，开始记账。历史账目会一直保留。"); Button(onClick = { openManagement("employees") }) { Text("添加员工") } } }
            if (activeTasks.isEmpty()) item { LedgerCard { Text("设置任务的员工工资和店铺收入，之后点击 + 即可记账。"); Button(onClick = { openManagement("tasks") }) { Text("配置任务") } } }
            items(active, key = { it.id }) { employee ->
                var expanded by rememberSaveable(employee.id) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(employee.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${counts.filter { it.employeeId == employee.id }.sumOf { it.count }}次 · ${Rules.money(employeeTotals.find { it.employeeId == employee.id }?.amount ?: 0)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { openDetail(employee.id, date) }) { Text("当天明细") }
                        IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起${employee.name}" else "展开${employee.name}") }
                    }
                    if (expanded) activeTasks.forEach { task ->
                        var note by rememberSaveable(date.toString(), employee.id, task.id) { mutableStateOf("") }
                        var quantity by rememberSaveable(date.toString(), employee.id, task.id) { mutableStateOf("1") }
                        var showNote by rememberSaveable(employee.id, task.id) { mutableStateOf(false) }
                        var busy by remember { mutableStateOf(false) }
                        val haptic = LocalHapticFeedback.current
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.name, fontWeight = FontWeight.Medium)
                                Text("${Rules.money(task.wageCents)}/次 · 已记${counts.find { it.employeeId == employee.id && it.taskTypeId == task.id }?.count ?: 0}次", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedTextField(quantity, { if (it.length <= 3 && it.all(Char::isDigit)) quantity = it }, label = { Text("次数") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(68.dp), enabled = !busy)
                            Spacer(Modifier.width(6.dp))
                            FilledIconButton(enabled = !busy, onClick = {
                                focus.clearFocus(); keyboard?.hide()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val submitted = note
                                val count = quantity.toIntOrNull() ?: 0
                                val record: () -> Unit = {
                                    busy = true
                                    vm.action("已记录${count}次工作", onSuccess = { if (note == submitted) note = ""; quantity = "1" }, onComplete = { busy = false }) {
                                        vm.ledger.addMany(employee.id, task.id, date, submitted, count)
                                    }
                                }
                                val covered = settlements.find { Rules.selectedTime(date) > it.periodStart && Rules.selectedTime(date) <= it.periodEnd }
                                if (covered != null && count in 1..999) {
                                    val before = Rules.total(settlementItems.filter { it.settlementId == covered.id }.map { it.amountCents })
                                    correctionConfirm = "补记${employee.name}的${task.name} ${count}次。\n原工资单合计由${Rules.money(before)}改为${Rules.money(before + task.wageCents * count)}。会保存修订记录，并排队发送修正版邮件。" to record
                                } else record()
                            }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "为${employee.name}记录${task.name}") }
                        }
                        TextButton(onClick = { showNote = !showNote }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (showNote) "收起备注" else "加备注（选填）", style = MaterialTheme.typography.labelSmall) }
                        if (showNote) OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text("本次备注（可选）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    }
                } }
            }
        }
    }
    if (calendar) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1900..2199)
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { vm.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; calendar = false
        }) { Text("选择") } }, dismissButton = { TextButton(onClick = { calendar = false }) { Text("取消") } }) { DatePicker(state) }
    }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("查询员工当日明细") }, text = {
        LazyColumn { items(employees, key = { it.id }) { employee -> TextButton(onClick = { history = false; openDetail(employee.id, date) }) { Text("${employee.name}${if (employee.isArchived) "（已停用）" else ""} · ${employee.id.take(6)}") } } }
    }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
    correctionConfirm?.let { (text, record) -> Confirm("补记并修正原工资单？", text, { correctionConfirm = null }, "补记并修正") { correctionConfirm = null; record() } }
}
