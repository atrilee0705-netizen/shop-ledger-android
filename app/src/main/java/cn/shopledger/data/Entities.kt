package cn.shopledger.data

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable @Entity(tableName = "employees")
data class Employee(@PrimaryKey val id: String, val name: String, val isArchived: Boolean = false, val createdAt: Long)

@Serializable @Entity(tableName = "tasks")
data class TaskType(@PrimaryKey val id: String, val name: String, val wageCents: Long, val revenueCents: Long, val isArchived: Boolean = false)

@Serializable @Entity(tableName = "entries", foreignKeys = [
    ForeignKey(entity = Employee::class, parentColumns = ["id"], childColumns = ["employeeId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = TaskType::class, parentColumns = ["id"], childColumns = ["taskTypeId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("employeeId"), Index("taskTypeId"), Index("completedAt"), Index("settlementId")])
data class WorkEntry(@PrimaryKey val id: String, val employeeId: String, val taskTypeId: String,
    val taskNameSnapshot: String, val wageCentsSnapshot: Long, val revenueCentsSnapshot: Long,
    val completedAt: Long, val note: String, val createdAt: Long, val settlementId: String? = null,
    @ColumnInfo(defaultValue = "10") val quantityTenths: Int = 10)

@Serializable @Entity(tableName = "settlements", indices = [Index(value = ["periodEnd"], unique = true)])
data class Settlement(@PrimaryKey val id: String, val periodStart: Long, val periodEnd: Long, val settledAt: Long, val isEarly: Boolean,
    @ColumnInfo(defaultValue = "1") val revision: Int = 1)

@Serializable @Entity(tableName = "settlement_adjustments", foreignKeys = [ForeignKey(entity = Settlement::class,
    parentColumns = ["id"], childColumns = ["settlementId"], onDelete = ForeignKey.CASCADE)], indices = [Index("settlementId")])
data class SettlementAdjustment(@PrimaryKey val id: String, val settlementId: String, val previousCents: Long,
    val updatedCents: Long, val reason: String, val correctedAt: Long)

@Serializable @Entity(tableName = "settlement_items", primaryKeys = ["settlementId", "employeeId"], foreignKeys = [
    ForeignKey(entity = Settlement::class, parentColumns = ["id"], childColumns = ["settlementId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Employee::class, parentColumns = ["id"], childColumns = ["employeeId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("employeeId")])
data class SettlementItem(val settlementId: String, val employeeId: String, val amountCents: Long)

// Delivery state stays on this device, separately from wage-slip business data.
@Entity(tableName = "settlement_delivery", foreignKeys = [ForeignKey(entity = Settlement::class,
    parentColumns = ["id"], childColumns = ["settlementId"], onDelete = ForeignKey.CASCADE)])
data class SettlementDelivery(@PrimaryKey val settlementId: String, val sentAt: Long? = null, val error: String? = null)

@Serializable @Entity(tableName = "settings")
data class Settings(@PrimaryKey val id: Int = 1, val settlementDay: Int = 25,
    val primaryColor: Long = 0xFF256C60, val backgroundFile: String? = null, val backgroundOpacity: Int = 12,
    val smtpHost: String = "", val smtpPort: Int = 465, val sender: String = "", val recipient: String = "",
    val backupHour: Int = 22, val backupMinute: Int = 0, val autoBackup: Boolean = false,
    @ColumnInfo(defaultValue = "1") val autoSettlement: Boolean = true,
    @ColumnInfo(defaultValue = "12") val settlementHour: Int = 12,
    @ColumnInfo(defaultValue = "0") val settlementMinute: Int = 0,
    @ColumnInfo(defaultValue = "1") val sendSettlementReport: Boolean = true)

// Device-only secrets and delivery state are deliberately excluded from backup snapshots.
@Entity(tableName = "local_state")
data class LocalState(@PrimaryKey val id: Int = 1, val smtpSecret: String? = null, val backupSecret: String? = null,
    val lastBackupAt: Long? = null, val lastSentAt: Long? = null, val lastError: String? = null,
    val pendingFile: String? = null, val pendingId: String? = null, val pendingAutoDay: String? = null,
    val lastAutoDay: String? = null)

@Entity(tableName = "recovery_profile")
data class RecoveryProfile(@PrimaryKey val id: Int = 1, val keyId: String, val keySecret: String,
    val createdAt: Long, val questionsJson: String? = null, val autoEmail: Boolean = true,
    val sentAt: Long? = null, val sentTo: String? = null, val lastError: String? = null)

@Entity(tableName = "recovery_attempts")
data class RecoveryAttempts(@PrimaryKey val id: Int = 1, val failures: Int = 0, val blockedUntil: Long = 0)

data class EmployeeTotal(val employeeId: String, val name: String, val amount: Long)
data class DailyTotal(val wage: Long, val revenue: Long)
data class TaskCount(val employeeId: String, val taskTypeId: String, val countTenths: Int)

// Original snapshot fields remain the historical per-task prices.
val WorkEntry.wageAmountCents: Long get() = cn.shopledger.Rules.quantityAmount(wageCentsSnapshot, quantityTenths)
val WorkEntry.revenueAmountCents: Long get() = cn.shopledger.Rules.quantityAmount(revenueCentsSnapshot, quantityTenths)
