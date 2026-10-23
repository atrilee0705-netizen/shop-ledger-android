package cn.shopledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.*
import cn.shopledger.data.*
import java.time.*

@Composable fun DetailScreen(vm: LedgerViewModel, employeeId: String, date: LocalDate, back: () -> Unit) {
    val employees by vm.employees.collectAsStateWithLifecycle()
    val flow = remember(employeeId, date) { vm.entries(employeeId, date) }
    val entries by flow.collectAsStateWithLifecycle(emptyList())
    val groups = remember(entries) { groupWorkEntries(entries) }
    var editing by remember { mutableStateOf<WorkEntryGroup?>(null) }
    var deleting by remember { mutableStateOf<WorkEntryGroup?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle(employees.find { it.id == employeeId }?.name ?: "员工明细", date.format(Rules.dateFormat), back)
        LedgerCard { Row { Amount("当日工资", Rules.total(entries.map { it.wageAmountCents }), Modifier.weight(1f)); Amount("贡献营业额", Rules.total(entries.map { it.revenueAmountCents }), Modifier.weight(1f)) } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (entries.isEmpty()) item { EmptyHint("这一天没有工作记录。") }
            items(groups, key = { it.id }) { group ->
                val entry = group.first
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${entry.taskNameSnapshot} · ${Rules.quantityText(group.quantityTenths)}次", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(Rules.money(group.wageCents), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("${Instant.ofEpochMilli(entry.completedAt).atZone(Rules.zone).toLocalTime().withNano(0)} · 店铺收入${Rules.money(group.revenueCents)}", style = MaterialTheme.typography.labelSmall)
                    if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                    if (entry.settlementId != null) Text("已列入工资单 · 补记会修正原单", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    else Row {
                        TextButton(onClick = { editing = group }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("修改", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { deleting = group }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                    }
                } }
            }
        }
    }
    editing?.let { group ->
        val entry = group.first
        var timestamp by remember(entry.id) { mutableStateOf(Rules.timestampFormat.format(Instant.ofEpochMilli(entry.completedAt))) }
        var note by remember(entry.id) { mutableStateOf(entry.note) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("修改工作记录") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(timestamp, { if (it.length <= 19) timestamp = it }, label = { Text("北京时间") }, supportingText = { Text("yyyy-MM-dd HH:mm:ss") }, singleLine = true)
                OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text("备注") }, maxLines = 4)
                Text("修改本次记录的${Rules.quantityText(group.quantityTenths)}次工作，时间和备注一起更新。当时的工资与收入快照保持不变。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            try {
                val time = Rules.editedTime(timestamp, entry.completedAt)
                Rules.checkTime(time); editing = null
                vm.action("记录已更新") { vm.ledger.editMany(group.ids, time, note) }
            } catch (_: Exception) { error = "请输入有效日期和时间" }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deleting?.let { group -> Confirm("删除这条记录？", "将删除本次记录的${Rules.quantityText(group.quantityTenths)}次工作，减少工资 ${Rules.money(group.wageCents)} 和营业额 ${Rules.money(group.revenueCents)}。", { deleting = null }, "删除") {
        deleting = null; vm.action("记录已删除") { vm.ledger.deleteMany(group.ids) }
    } }
}
