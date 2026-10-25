package cn.shopledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.*
import cn.shopledger.data.*
import java.time.*

@Composable fun SettlementScreen(vm: LedgerViewModel) {
    val pending by vm.pendingEntries.collectAsStateWithLifecycle()
    val settlements by vm.settlements.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val employees by vm.employees.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val deliveries by vm.deliveries.collectAsStateWithLifecycle()
    val adjustments by vm.adjustments.collectAsStateWithLifecycle()
    var queryOnly by rememberSaveable { mutableStateOf(false) }
    var throughText by rememberSaveable { mutableStateOf(Rules.today().minusDays(1).toString()) }
    var fromText by rememberSaveable { mutableStateOf(Rules.today().withDayOfMonth(1).toString()) }
    var rangeEndText by rememberSaveable { mutableStateOf(Rules.today().toString()) }
    var confirm by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Settlement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    val through = LocalDate.parse(throughText)
    val from = LocalDate.parse(fromText)
    val rangeEnd = LocalDate.parse(rangeEndText)
    val rangeFlow = remember(from, rangeEnd) { vm.rangeEntries(from, rangeEnd) }
    val range by rangeFlow.collectAsStateWithLifecycle(emptyList())
    val payable = pending.filter { it.completedAt <= Rules.end(through) }
    val total = Rules.total(payable.map { it.wageAmountCents })
    val lastEnd = settlements.firstOrNull()?.periodEnd ?: Rules.beginning
    val validEnd = Rules.end(through) > lastEnd && through < Rules.today()
    Column {
        PageTitle("工资结算", "正式结账存工资单，只查金额不影响结账")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(!queryOnly, { queryOnly = false }, { Text("接着上次结账") })
                FilterChip(queryOnly, { queryOnly = true }, { Text("只查金额") })
            } }
            if (queryOnly) item { LedgerCard {
                Text("选哪几天，就看哪几天", style = MaterialTheme.typography.titleMedium)
                DateChoice("从", from) { fromText = it.toString() }
                DateChoice("到（包含这一天）", rangeEnd) { rangeEndText = it.toString() }
                if (from > rangeEnd) Text("开始日期不能晚于结束日期", color = MaterialTheme.colorScheme.error)
                else {
                    Row { Amount("员工工资", Rules.total(range.map { it.wageAmountCents }), Modifier.weight(1f)); Amount("店铺营业额", Rules.total(range.map { it.revenueAmountCents }), Modifier.weight(1f)) }
                    WageRows(range, employees)
                    Text("共${Rules.quantityText(range.sumOf { it.quantityTenths })}次工作 · 按工作日期统计，含已结账和未结账的记录。", style = MaterialTheme.typography.bodySmall)
                    if (rangeEnd > Rules.today()) Text("含未来的预记账目，请与已完成的工作区分。", color = MaterialTheme.colorScheme.primary)
                }
                Text("这里只查看，不生成工资单，不改变下次结账的起点。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            } }
            else {
                item { LedgerCard {
                    Text("这次要结多少工资", style = MaterialTheme.typography.titleMedium)
                    Text(if (lastEnd == Rules.beginning) "从首次记账开始" else "${Rules.timestampFormat.format(Instant.ofEpochMilli(lastEnd))}之后接着算", style = MaterialTheme.typography.bodySmall)
                    DateChoice("算到这一天结束", through, Rules.today().minusDays(1)) { throughText = it.toString() }
                    Text(Rules.periodText(lastEnd, Rules.end(through)), style = MaterialTheme.typography.bodySmall)
                    Row {
                        Amount("全员工资", total, Modifier.weight(1f))
                        Amount("店铺总营业额", Rules.total(payable.map { it.revenueAmountCents }), Modifier.weight(1f))
                    }
                    WageRows(payable, employees)
                    if (!validEnd) Text("请选择上次截止日之后、昨天及以前的日期。", color = MaterialTheme.colorScheme.error)
                    Text("今天和未来的账目不会进入这次结账。保存工资单不代表已经付款。", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = validEnd && payable.isNotEmpty() && !busy, onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("保存工资单") }
                } }
                item { LedgerCard {
                    Text(if (settings.autoSettlement) "每月${settings.settlementDay}日 ${"%02d:%02d".format(settings.settlementHour, settings.settlementMinute)}自动结账" else "自动结账已关闭", style = MaterialTheme.typography.titleMedium)
                    Text("自动结账只算到结账日前一天23:59。提前结账后，固定日期接着上次截止时间算。日期和报告时间可在设置里修改。", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item { Text("已保存的工资单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { OutlinedTextField(historyQuery, { if (it.length <= 10) historyQuery = it }, label = { Text("查截止月份，例如2026-10") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (settlements.isEmpty()) item { EmptyHint("还没有工资单。可以提前结账，也可以等固定结账日自动生成。") }
            items(settlements.filter { Rules.today(it.periodEnd).toString().contains(historyQuery.trim()) }, key = { it.id }) { settlement ->
                val rows = items.filter { it.settlementId == settlement.id }
                val corrections = adjustments.filter { it.settlementId == settlement.id }
                val delivery = deliveries.find { it.settlementId == settlement.id }
                val revenueFlow = remember(settlement.id) { vm.settlementRevenue(settlement.id) }
                val revenue by revenueFlow.collectAsStateWithLifecycle<Long?>(null)
                var expanded by rememberSaveable(settlement.id) { mutableStateOf(false) }
                LedgerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("截至${Rules.today(settlement.periodEnd).format(Rules.dateFormat)}", fontWeight = FontWeight.Bold)
                            Text("${if (settlement.isEarly) "手动结账" else "固定日结账"} · 第${settlement.revision}版", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(Rules.periodText(settlement.periodStart, settlement.periodEnd), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth()) {
                        Amount("全员工资", Rules.total(rows.map { it.amountCents }), Modifier.weight(1f))
                        val income = revenue
                        if (income == null) Text("店铺总营业额读取中", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        else Amount("店铺总营业额", income, Modifier.weight(1f))
                    }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起工资明细" else "展开工资明细${if (corrections.isNotEmpty()) "与补记记录" else ""}") }
                    if (expanded) {
                        val workFlow = remember(settlement.id) { vm.settledEntries(settlement.id) }
                        val work by workFlow.collectAsStateWithLifecycle<List<WorkEntry>?>(null)
                        val details = work
                        if (details == null) Text("正在读取项目次数…", style = MaterialTheme.typography.bodySmall)
                        else WageRows(details, employees, rows)
                        corrections.forEach { Text("${Rules.timestampFormat.format(Instant.ofEpochMilli(it.correctedAt))}\n${it.reason}\n合计 ${Rules.money(it.previousCents)} → ${Rules.money(it.updatedCents)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(when {
                        delivery?.error != null -> "邮件待发送：${delivery.error}"
                        delivery?.sentAt == 0L -> "历史工资单（不自动重发）"
                        delivery?.sentAt != null -> "本版邮件已提交至邮箱服务"
                        !settings.sendSettlementReport -> "报告自动发送已关闭"
                        settlement.revision > 1 -> "待发送修正版"
                        else -> "报告待发送"
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (settlement.id == settlements.firstOrNull()?.id) TextButton(enabled = !busy, onClick = { undo = settlement }) { Text("撤销最近一期结账") }
                }
            }
        }
    }
    if (confirm) Confirm("保存这份工资单？", "${Rules.periodText(lastEnd, Rules.end(through))}\n共${Rules.quantityText(payable.sumOf { it.quantityTenths })}次工作，工资${Rules.money(total)}。\n以后补记这段日期的工作，会修正这份工资单，不会重复算到下一期。", { confirm = false }, "确认保存") {
        confirm = false; busy = true
        vm.action("工资单已保存", onComplete = { busy = false }) { vm.ledger.settle(true, through = through) }
    }
    undo?.let { settlement -> Confirm("撤销最近一期结账？", "这份工资单及其修订记录会删除，对应工作重新变为未结账。已发出的邮件无法收回。若自动结账已到期，之后仍会重新出单。", { undo = null }, "撤销结账") {
        undo = null; busy = true
        vm.action("最近一期结账已撤销", onComplete = { busy = false }) { vm.ledger.undo(settlement.id) }
    } }
}

@Composable private fun WageRows(entries: List<WorkEntry>, employees: List<Employee>, saved: List<SettlementItem>? = null) {
    val sums = entries.groupBy { it.employeeId }
    val ids = saved?.map { it.employeeId } ?: employees.filter { it.id in sums }.map { it.id }
    ids.forEach { id ->
        val work = sums[id].orEmpty()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(employees.find { it.id == id }?.name ?: "历史员工", Modifier.weight(1f))
                Text(Rules.money(saved?.find { it.employeeId == id }?.amountCents ?: Rules.total(work.map { it.wageAmountCents })))
            }
            taskQuantities(work).forEach { task ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                    Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${Rules.quantityText(task.quantityTenths)}次", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
