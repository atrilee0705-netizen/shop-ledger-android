package cn.shopledger.data

import androidx.room.withTransaction
import cn.shopledger.Rules
import java.time.LocalDate
import java.util.UUID

class Ledger(val db: LedgerDb) {
    val dao = db.dao()
    suspend fun saveEmployee(id: String?, name: String, archived: Boolean = false) = db.withTransaction {
        val old = id?.let { requireNotNull(dao.employee(it)) { "员工不存在" } }
        dao.saveEmployee(Employee(old?.id ?: uuid(), Rules.name(name), archived, old?.createdAt ?: Rules.now()))
    }
    suspend fun saveTask(id: String?, name: String, wage: Long, revenue: Long, archived: Boolean = false) = db.withTransaction {
        require(wage in 0..Rules.MAX_CENTS && revenue in 0..Rules.MAX_CENTS) { "金额超出范围" }
        id?.let { requireNotNull(dao.task(it)) { "任务不存在" } }
        dao.saveTask(TaskType(id ?: uuid(), Rules.name(name), wage, revenue, archived))
    }
    suspend fun add(employeeId: String, taskId: String, date: LocalDate, note: String, now: Long = Rules.now()): WorkEntry =
        addMany(employeeId, taskId, date, note, 1, now).first()

    suspend fun addMany(employeeId: String, taskId: String, date: LocalDate, note: String, quantity: Int, now: Long = Rules.now()): List<WorkEntry> {
        require(quantity in 1..999) { "次数请输入1至999" }
        return addQuantity(employeeId, taskId, date, note, quantity * 10, now)
    }
    suspend fun addQuantity(employeeId: String, taskId: String, date: LocalDate, note: String, quantityTenths: Int, now: Long = Rules.now()): List<WorkEntry> = db.withTransaction {
        require(quantityTenths in 1..Rules.MAX_QUANTITY_TENTHS) { "次数请输入0.1至999，最多一位小数" }
        val whole = quantityTenths / 10
        val remainder = quantityTenths % 10
        val lineCount = whole + if (remainder == 0) 0 else 1
        require(dao.entryCount() + lineCount <= Rules.MAX_ROWS) { "记录已达容量上限，请先保留完整备份" }
        val employee = requireNotNull(dao.employee(employeeId)) { "员工不存在" }
        val task = requireNotNull(dao.task(taskId)) { "任务不存在" }
        require(!employee.isArchived && !task.isArchived) { "员工或任务已停用" }
        val at = Rules.selectedTime(date, now)
        Rules.checkTime(at); Rules.checkTime(now)
        val checkedNote = checkedNote(note)
        val original = dao.coveringSettlement(at)
        // Keep whole-task rows individually editable, as in older versions; save the
        // remaining 0.1–0.9 task as one explicitly labelled fractional row.
        val rows = List(lineCount) { index -> WorkEntry(uuid(), employeeId, taskId, task.name, task.wageCents, task.revenueCents,
            at, checkedNote, now, original?.id, if (index < whole) 10 else remainder) }
        dao.insertEntries(rows)
        if (original != null) {
            val before = Rules.total(dao.settlementItems().filter { it.settlementId == original.id }.map { it.amountCents })
            val updated = wageItems(original.id, dao.settledEntries(original.id))
            dao.clearSettlementItems(original.id); dao.insertItems(updated)
            dao.updateSettlement(original.copy(revision = original.revision + 1))
            dao.insertAdjustments(listOf(SettlementAdjustment(uuid(), original.id, before, Rules.total(updated.map { it.amountCents }),
                "补记${employee.name}的${task.name} ${Rules.quantityText(quantityTenths)}次（${date.format(Rules.dateFormat)}）", now)))
            dao.saveDelivery(SettlementDelivery(original.id))
        }
        rows
    }
    suspend fun edit(id: String, completedAt: Long, note: String) = editMany(listOf(id), completedAt, note)
    suspend fun editMany(ids: List<String>, completedAt: Long, note: String) = db.withTransaction {
        require(ids.isNotEmpty()) { "没有可修改的记录" }
        val entries = ids.distinct().map { requireNotNull(dao.entry(it)) { "记录不存在，请返回明细重新选择" } }
        require(entries.all { it.settlementId == null }) { "这条记录已列入工资单，请先撤销最近一期结账再修改" }
        Rules.checkTime(completedAt)
        require(dao.coveringSettlement(completedAt) == null) { "移入已结账日期请用补记功能，原记录可先删除" }
        val checked = checkedNote(note)
        entries.forEach { dao.updateEntry(it.copy(completedAt = completedAt, note = checked)) }
    }
    suspend fun delete(id: String) = deleteMany(listOf(id))
    suspend fun deleteMany(ids: List<String>) = db.withTransaction {
        require(ids.isNotEmpty()) { "没有可删除的记录" }
        val entries = ids.distinct().map { requireNotNull(dao.entry(it)) { "记录不存在，请返回明细重新选择" } }
        require(entries.all { it.settlementId == null }) { "这条记录已列入工资单，请先撤销最近一期结账再删除" }
        entries.forEach { dao.deleteEntry(it.id) }
    }
    // Only whole, finished Beijing days can be closed. Membership freezes each slip;
    // a later backfill is assigned to its original slip and creates a revision.
    suspend fun settle(early: Boolean, now: Long = Rules.now(), through: LocalDate = Rules.today(now).minusDays(1), allowEmpty: Boolean = false): Settlement = db.withTransaction {
        Rules.checkTime(now)
        val previous = dao.latestSettlement()
        require(through < Rules.today(now)) { "正式结账最多只能算到昨天，今天和未来的账留到以后" }
        val end = Rules.end(through)
        Rules.checkTime(end)
        val after = previous?.periodEnd ?: Rules.beginning
        require(end > after) { "截止日期必须晚于上次结账截止日" }
        val entries = dao.payable(end)
        require(allowEmpty || entries.isNotEmpty()) { "这段时间没有待结账的工作记录" }
        val settings = dao.settings() ?: Settings()
        if (!early) {
            val due = Rules.settlementDue(settings.settlementDay, previous?.periodEnd, dao.entries().minOfOrNull { it.completedAt }, now)
            require(Rules.today(now) >= due) { "尚未到固定结算日，请使用提前结算" }
            require(through == due.minusDays(1)) { "自动结账只算到结账日前一天" }
        }
        val start = previous?.periodEnd ?: Rules.beginning
        val settlement = Settlement(uuid(), start, end, now, early)
        val items = wageItems(settlement.id, entries)
        dao.insertSettlement(settlement)
        dao.insertItems(items)
        dao.assignSettlement(settlement.id, end)
        dao.saveDelivery(SettlementDelivery(settlement.id))
        settlement
    }
    suspend fun settleAutomatically(now: Long = Rules.now()): List<Settlement> = db.withTransaction {
        val settings = dao.settings() ?: Settings()
        if (!settings.autoSettlement) return@withTransaction emptyList()
        val first = dao.entries().minOfOrNull { it.completedAt } ?: return@withTransaction emptyList()
        val created = mutableListOf<Settlement>()
        // Catch up missed cycles without changing any original monthly cutoff.
        while (true) {
            val due = Rules.settlementDue(settings.settlementDay, dao.latestSettlement()?.periodEnd, first, now)
            val planned = due.atTime(settings.settlementHour, settings.settlementMinute).atZone(Rules.zone).toInstant().toEpochMilli()
            if (planned > now) break
            created += settle(false, now, due.minusDays(1), allowEmpty = true)
        }
        created
    }
    private fun wageItems(id: String, entries: List<WorkEntry>): List<SettlementItem> =
        entries.groupBy { it.employeeId }.map { (employee, rows) ->
            SettlementItem(id, employee, Rules.total(rows.map { it.wageAmountCents }))
        }.also { Rules.total(it.map { row -> row.amountCents }) }
    suspend fun undo(id: String) = db.withTransaction {
        val latest = requireNotNull(dao.latestSettlement()) { "没有可撤销的结算" }
        require(latest.id == id) { "只允许撤销最近一次结算" }
        dao.releaseSettlement(id)
        dao.deleteSettlement(id)
    }
    suspend fun updateSettings(change: (Settings) -> Settings) = db.withTransaction {
        val updated = change(dao.settings() ?: Settings())
        require(updated.settlementDay in 1..31 && updated.backgroundOpacity in 0..40)
        require(updated.backupHour in 0..23 && updated.backupMinute in 0..59)
        require(updated.settlementHour in 0..23 && updated.settlementMinute in 0..59)
        dao.saveSettings(updated)
    }
    private fun checkedNote(note: String): String = note.trim().also { require(it.length <= 1000) { "备注最多1000个字符" } }
    companion object { fun uuid(): String = UUID.randomUUID().toString() }
}
