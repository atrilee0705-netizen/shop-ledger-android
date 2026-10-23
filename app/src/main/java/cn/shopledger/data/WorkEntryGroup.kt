package cn.shopledger.data

import cn.shopledger.Rules

/** Presentation only: the original rows and their rounded historical money stay intact. */
data class WorkEntryGroup(val rows: List<WorkEntry>) {
    val first get() = rows.first()
    val id get() = first.id
    val ids get() = rows.map { it.id }
    val quantityTenths get() = rows.sumOf { it.quantityTenths }
    val wageCents get() = Rules.total(rows.map { it.wageAmountCents })
    val revenueCents get() = Rules.total(rows.map { it.revenueAmountCents })
}

private data class SubmissionKey(val employeeId: String, val taskId: String, val createdAt: Long,
    val completedAt: Long, val name: String, val wage: Long, val revenue: Long, val note: String, val settlementId: String?)

fun groupWorkEntries(entries: List<WorkEntry>): List<WorkEntryGroup> = entries.groupBy {
    // Each addQuantity transaction gives all of its rows the same exact timestamps.
    // Other fields prevent edited rows, historical prices or payrolls being mixed.
    SubmissionKey(it.employeeId, it.taskTypeId, it.createdAt, it.completedAt, it.taskNameSnapshot,
        it.wageCentsSnapshot, it.revenueCentsSnapshot, it.note, it.settlementId)
}.values.map(::WorkEntryGroup)
