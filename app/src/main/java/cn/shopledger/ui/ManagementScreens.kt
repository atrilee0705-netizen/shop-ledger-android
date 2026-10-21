package cn.shopledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.*
import cn.shopledger.data.*

@Composable fun EmployeeScreen(vm: LedgerViewModel, back: () -> Unit) {
    val employees by vm.employees.collectAsStateWithLifecycle()
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Employee?>(null) }
    var adding by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf<Employee?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("员工管理", "停用后，所有历史账目继续保留", back)
        Button(onClick = { adding = true }, Modifier.fillMaxWidth()) { Text("添加员工") }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("显示已停用员工", Modifier.weight(1f)); Switch(showArchived, { showArchived = it }) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(employees.filter { showArchived || !it.isArchived }, key = { it.id }) { employee ->
                LedgerCard {
                    Text(employee.name, style = MaterialTheme.typography.titleMedium)
                    Text("${if (employee.isArchived) "已停用" else "在职"} · 编号 ${employee.id.take(8)}", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { editing = employee }) { Text("修改姓名") }
                        TextButton(onClick = { archiving = employee }) { Text(if (employee.isArchived) "恢复使用" else "停用") }
                    }
                }
            }
            if (employees.isEmpty()) item { EmptyHint("暂无员工。允许重名，员工通过独立编号区分。") }
        }
    }
    if (adding || editing != null) {
        val employee = editing
        var name by remember(employee?.id) { mutableStateOf(employee?.name ?: "") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) { adding = false; editing = null } }, title = { Text(if (employee == null) "添加员工" else "修改姓名") },
            text = { OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("员工姓名") }, singleLine = true) },
            confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                vm.action("员工已保存", onSuccess = { adding = false; editing = null }) { try { vm.ledger.saveEmployee(employee?.id, name, employee?.isArchived ?: false) } finally { busy = false } }
            }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = { adding = false; editing = null }) { Text("取消") } })
    }
    archiving?.let { employee -> Confirm(if (employee.isArchived) "恢复员工" else "停用员工", "${employee.name}的历史记录、工资单和营业额都会保留。", { archiving = null }) {
        archiving = null
        vm.action("员工状态已更新") { vm.ledger.saveEmployee(employee.id, employee.name, !employee.isArchived) }
    } }
}

@Composable fun TaskScreen(vm: LedgerViewModel, back: () -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskType?>(null) }
    var adding by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf<TaskType?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("任务管理", "每次记账保存当时的名称与两种金额", back)
        Button(onClick = { adding = true }, Modifier.fillMaxWidth()) { Text("添加任务") }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("显示已停用任务", Modifier.weight(1f)); Switch(showArchived, { showArchived = it }) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(tasks.filter { showArchived || !it.isArchived }, key = { it.id }) { task ->
                LedgerCard {
                    Text("${task.name}${if (task.isArchived) "（已停用）" else ""}", style = MaterialTheme.typography.titleMedium)
                    Row { Amount("员工工资 / 次", task.wageCents, Modifier.weight(1f)); Amount("店铺收入 / 次", task.revenueCents, Modifier.weight(1f)) }
                    Row { TextButton(onClick = { editing = task }) { Text("修改") }; TextButton(onClick = { archiving = task }) { Text(if (task.isArchived) "恢复使用" else "停用") } }
                }
            }
            if (tasks.isEmpty()) item { EmptyHint("暂无任务。工资和店铺收入分别填写，可设为零。") }
        }
    }
    if (adding || editing != null) {
        val task = editing
        var name by remember(task?.id) { mutableStateOf(task?.name ?: "") }
        var wage by remember(task?.id) { mutableStateOf(task?.wageCents?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
        var revenue by remember(task?.id) { mutableStateOf(task?.revenueCents?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { adding = false; editing = null }, title = { Text(if (task == null) "添加任务" else "修改任务") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("任务名称") }, singleLine = true)
                MoneyField(wage, { wage = it }, "员工工资（元 / 次）")
                MoneyField(revenue, { revenue = it }, "店铺收入（元 / 次）")
                Text("修改仅影响今后新增的记录。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            try {
                val w = Rules.parseMoney(wage); val r = Rules.parseMoney(revenue); val n = Rules.name(name)
                adding = false; editing = null
                vm.action("任务已保存") { vm.ledger.saveTask(task?.id, n, w, r, task?.isArchived ?: false) }
            } catch (e: Exception) { error = e.message }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("取消") } })
    }
    archiving?.let { task -> Confirm(if (task.isArchived) "恢复任务" else "停用任务", "历史任务名称、工资和收入快照都会保留。", { archiving = null }) {
        archiving = null
        vm.action("任务状态已更新") { vm.ledger.saveTask(task.id, task.name, task.wageCents, task.revenueCents, !task.isArchived) }
    } }
}
@Composable private fun MoneyField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, { if (it.length <= 14) change(it) }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
