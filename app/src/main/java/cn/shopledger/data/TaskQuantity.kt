package cn.shopledger.data

/** Counts use historical task names and tenths, including archived tasks and employees. */
data class TaskQuantity(val taskId: String, val name: String, val quantityTenths: Int)

fun taskQuantities(entries: List<WorkEntry>): List<TaskQuantity> = entries
    .groupBy { it.taskTypeId to it.taskNameSnapshot }
    .map { (task, rows) -> TaskQuantity(task.first, task.second, rows.sumOf { it.quantityTenths }) }
