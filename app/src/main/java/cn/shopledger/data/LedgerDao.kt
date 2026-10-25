package cn.shopledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM recovery_profile WHERE id=1") fun recoveryFlow(): Flow<RecoveryProfile?>
    @Query("SELECT * FROM recovery_profile WHERE id=1") suspend fun recovery(): RecoveryProfile?
    @Upsert suspend fun saveRecovery(profile: RecoveryProfile)
    @Query("DELETE FROM recovery_profile") suspend fun clearRecovery()
    @Query("SELECT * FROM recovery_attempts WHERE id=1") suspend fun recoveryAttempts(): RecoveryAttempts?
    @Upsert suspend fun saveRecoveryAttempts(attempts: RecoveryAttempts)
    @Query("SELECT * FROM employees ORDER BY createdAt, id") fun employeesFlow(): Flow<List<Employee>>
    @Query("SELECT * FROM tasks ORDER BY rowid") fun tasksFlow(): Flow<List<TaskType>>
    @Query("SELECT * FROM settings WHERE id=1") fun settingsFlow(): Flow<Settings?>
    @Query("SELECT * FROM local_state WHERE id=1") fun localStateFlow(): Flow<LocalState?>
    @Query("SELECT * FROM settlements ORDER BY periodEnd DESC") fun settlementsFlow(): Flow<List<Settlement>>
    @Query("SELECT * FROM settlement_items") fun settlementItemsFlow(): Flow<List<SettlementItem>>
    @Query("SELECT * FROM settlement_delivery") fun deliveryFlow(): Flow<List<SettlementDelivery>>
    @Query("SELECT * FROM settlement_adjustments ORDER BY correctedAt") fun adjustmentsFlow(): Flow<List<SettlementAdjustment>>
    @Query("SELECT * FROM settlement_adjustments ORDER BY correctedAt") suspend fun adjustments(): List<SettlementAdjustment>
    @Insert suspend fun insertAdjustments(rows: List<SettlementAdjustment>)
    @Update suspend fun updateSettlement(row: Settlement)
    @Query("DELETE FROM settlement_items WHERE settlementId=:id") suspend fun clearSettlementItems(id: String)
    @Query("SELECT * FROM settlements WHERE id=:id") suspend fun settlement(id: String): Settlement?
    @Query("SELECT * FROM settlements WHERE periodStart<:at AND periodEnd>=:at LIMIT 1") suspend fun coveringSettlement(at: Long): Settlement?
    @Query("SELECT * FROM entries WHERE settlementId IS NULL ORDER BY completedAt, id") fun pendingEntriesFlow(): Flow<List<WorkEntry>>
    @Query("SELECT * FROM entries WHERE completedAt>=:start AND completedAt<:end ORDER BY completedAt, id") fun rangeEntriesFlow(start: Long, end: Long): Flow<List<WorkEntry>>
    @Query("SELECT * FROM entries WHERE settlementId IS NULL AND completedAt<=:end ORDER BY completedAt, id") suspend fun payable(end: Long): List<WorkEntry>
    @Query("SELECT * FROM entries WHERE settlementId=:id ORDER BY completedAt, id") suspend fun settledEntries(id: String): List<WorkEntry>
    @Query("SELECT * FROM entries WHERE settlementId=:id ORDER BY completedAt, id") fun settledEntriesFlow(id: String): Flow<List<WorkEntry>>
    @Query("SELECT COALESCE(SUM((revenueCentsSnapshot * quantityTenths + 5) / 10),0) FROM entries WHERE settlementId=:id")
    fun settlementRevenueFlow(id: String): Flow<Long>
    @Query("UPDATE entries SET settlementId=:settlementId WHERE settlementId IS NULL AND completedAt<=:end") suspend fun assignSettlement(settlementId: String, end: Long)
    @Query("UPDATE entries SET settlementId=NULL WHERE settlementId=:id") suspend fun releaseSettlement(id: String)
    @Upsert suspend fun saveDelivery(state: SettlementDelivery)
    @Query("SELECT * FROM settlement_delivery WHERE settlementId=:id") suspend fun delivery(id: String): SettlementDelivery?
    @Query("SELECT s.* FROM settlements s JOIN settlement_delivery d ON s.id=d.settlementId WHERE d.sentAt IS NULL ORDER BY s.periodEnd") suspend fun unsentSettlements(): List<Settlement>
    @Query("SELECT COALESCE(SUM((wageCentsSnapshot * quantityTenths + 5) / 10),0) AS wage, COALESCE(SUM((revenueCentsSnapshot * quantityTenths + 5) / 10),0) AS revenue FROM entries WHERE completedAt >= :start AND completedAt < :end")
    fun dailyTotal(start: Long, end: Long): Flow<DailyTotal>
    @Query("SELECT e.id AS employeeId, e.name, COALESCE(SUM((w.wageCentsSnapshot * w.quantityTenths + 5) / 10),0) AS amount FROM employees e LEFT JOIN entries w ON w.employeeId=e.id AND w.completedAt>=:start AND w.completedAt<:end GROUP BY e.id ORDER BY e.createdAt, e.id")
    fun employeeTotals(start: Long, end: Long): Flow<List<EmployeeTotal>>
    @Query("SELECT employeeId, taskTypeId, SUM(quantityTenths) AS countTenths FROM entries WHERE completedAt>=:start AND completedAt<:end GROUP BY employeeId, taskTypeId")
    fun taskCounts(start: Long, end: Long): Flow<List<TaskCount>>
    @Query("SELECT * FROM entries WHERE employeeId=:employeeId AND completedAt>=:start AND completedAt<:end ORDER BY completedAt, createdAt, id")
    fun entryFlow(employeeId: String, start: Long, end: Long): Flow<List<WorkEntry>>
    @Query("SELECT * FROM entries WHERE completedAt>:after AND completedAt<=:end ORDER BY completedAt, id") suspend fun unsettled(after: Long, end: Long): List<WorkEntry>
    @Query("SELECT * FROM employees") suspend fun employees(): List<Employee>
    @Query("SELECT * FROM tasks") suspend fun tasks(): List<TaskType>
    @Query("SELECT * FROM entries") suspend fun entries(): List<WorkEntry>
    @Query("SELECT * FROM settlements ORDER BY periodEnd") suspend fun settlements(): List<Settlement>
    @Query("SELECT * FROM settlement_items") suspend fun settlementItems(): List<SettlementItem>
    @Query("SELECT * FROM employees WHERE id=:id") suspend fun employee(id: String): Employee?
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskType?
    @Query("SELECT * FROM entries WHERE id=:id") suspend fun entry(id: String): WorkEntry?
    @Query("SELECT * FROM settlements ORDER BY periodEnd DESC LIMIT 1") suspend fun latestSettlement(): Settlement?
    @Query("SELECT * FROM settings WHERE id=1") suspend fun settings(): Settings?
    @Query("SELECT * FROM local_state WHERE id=1") suspend fun localState(): LocalState?
    @Query("SELECT COUNT(*) FROM entries") suspend fun entryCount(): Int
    @Query("SELECT MIN(completedAt) FROM entries") fun firstEntryFlow(): Flow<Long?>
    @Upsert suspend fun saveEmployee(employee: Employee)
    @Upsert suspend fun saveTask(task: TaskType)
    @Insert suspend fun insertEntry(entry: WorkEntry)
    @Update suspend fun updateEntry(entry: WorkEntry)
    @Query("DELETE FROM entries WHERE id=:id") suspend fun deleteEntry(id: String)
    @Insert suspend fun insertSettlement(settlement: Settlement)
    @Insert suspend fun insertItems(items: List<SettlementItem>)
    @Query("DELETE FROM settlements WHERE id=:id") suspend fun deleteSettlement(id: String)
    @Upsert suspend fun saveSettings(settings: Settings)
    @Upsert suspend fun saveLocalState(state: LocalState)
    @Insert suspend fun insertEmployees(rows: List<Employee>)
    @Insert suspend fun insertTasks(rows: List<TaskType>)
    @Insert suspend fun insertEntries(rows: List<WorkEntry>)
    @Insert suspend fun insertSettlements(rows: List<Settlement>)
    @Query("DELETE FROM settlement_items") suspend fun clearItems()
    @Query("DELETE FROM settlements") suspend fun clearSettlements()
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM employees") suspend fun clearEmployees()
}
