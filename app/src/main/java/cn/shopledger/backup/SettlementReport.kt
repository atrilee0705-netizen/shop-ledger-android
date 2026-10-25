package cn.shopledger.backup

import cn.shopledger.Rules
import cn.shopledger.data.*
import java.time.Instant

/** Render one saved payroll; never count by current prices or a later date range. */
object SettlementReport {
    fun text(slip: Settlement, employees: List<Employee>, items: List<SettlementItem>, entries: List<WorkEntry>,
        adjustments: List<SettlementAdjustment>): String {
        val wages = items.filter { it.settlementId == slip.id }
        val work = entries.filter { it.settlementId == slip.id }
        val names = employees.associate { it.id to it.name }
        val byEmployee = work.groupBy { it.employeeId }
        return buildString {
            appendLine("工资单 · 第${slip.revision}版${if (slip.revision > 1) "（修正版，请以本版为准）" else ""}")
            appendLine(Rules.periodText(slip.periodStart, slip.periodEnd))
            wages.forEach { wage ->
                appendLine("${names[wage.employeeId] ?: "历史员工"}：${Rules.money(wage.amountCents)}")
                taskQuantities(byEmployee[wage.employeeId].orEmpty()).forEach { task ->
                    appendLine("  ${task.name}：${Rules.quantityText(task.quantityTenths)}次")
                }
            }
            appendLine("全员工资：${Rules.money(Rules.total(wages.map { it.amountCents }))}")
            appendLine("店铺总营业额：${Rules.money(Rules.total(work.map { it.revenueAmountCents }))}")
            adjustments.filter { it.settlementId == slip.id }.forEach {
                appendLine("${Rules.timestampFormat.format(Instant.ofEpochMilli(it.correctedAt))} ${it.reason}；合计由${Rules.money(it.previousCents)}改为${Rules.money(it.updatedCents)}")
            }
            appendLine("这是工资核算报告。请按店铺实际情况支付工资。编号：${slip.id}")
        }
    }
}
