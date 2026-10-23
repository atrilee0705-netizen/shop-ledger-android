package cn.shopledger.backup

import cn.shopledger.Rules
import cn.shopledger.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class LedgerSnapshot(val version: Int = 3, val exportedAt: Long,
    val employees: List<Employee>, val tasks: List<TaskType>, val entries: List<WorkEntry>,
    val settlements: List<Settlement>, val items: List<SettlementItem>, val settings: Settings,
    val backgroundBase64: String? = null, val adjustments: List<SettlementAdjustment> = emptyList())

object BackupFormat {
    internal fun encodeSnapshot(snapshot: LedgerSnapshot): ByteArray {
        validate(snapshot)
        return json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
    }
    internal fun decodeSnapshot(bytes: ByteArray): LedgerSnapshot = json.decodeFromString<LedgerSnapshot>(bytes.toString(Charsets.UTF_8)).also(::validate)
    fun normalize(s: LedgerSnapshot): LedgerSnapshot = if (s.version >= 2) s.copy(version = 3) else s.copy(version = 3,
        entries = s.entries.map { entry -> entry.copy(settlementId = s.settlements.find {
            entry.completedAt > it.periodStart && entry.completedAt <= it.periodEnd
        }?.id) })
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val random = SecureRandom()
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(snapshot: LedgerSnapshot, password: CharArray): ByteArray {
        require(password.size in 6..256) { "备份密码需为6至256位，数字也可以" }
        validate(snapshot)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.write(byteArrayOf(0x53, 0x4c, 0x42, 0x4b)); it.writeInt(1); it.writeInt(ITERATIONS); it.write(salt); it.write(nonce)
        } }.toByteArray()
        val payload = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BYTES - header.size - 16) { "账本超过64MB备份容量，未生成不完整文件" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): LedgerSnapshot {
        require(bytes.size in 56..MAX_BYTES) { "备份文件长度无效或超过64MB" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x534c424b) { "不支持此备份格式" }
        val version = input.readInt()
        if (version == 2) return RecoveryFormat.decryptPassword(bytes, password)
        require(version == 1) { "不支持此备份版本" }
        require(input.readInt() == ITERATIONS) { "不支持此密钥参数" }
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(bytes.copyOfRange(0, 40))
        val plain = try { cipher.doFinal(bytes, 40, bytes.size - 40) }
        catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("备份密码错误或文件已损坏，当前账本未改动") }
        return json.decodeFromString<LedgerSnapshot>(plain.toString(Charsets.UTF_8)).also(::validate)
    }
    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= limit) { "文件超出允许大小" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
    fun validate(s: LedgerSnapshot) {
        require(s.version in 1..3) { "不支持此账本版本" }
        Rules.checkTime(s.exportedAt)
        require(s.entries.size <= Rules.MAX_ROWS && s.employees.size <= 100_000 && s.tasks.size <= 100_000)
        fun <T> unique(rows: List<T>, id: (T) -> String): Set<String> {
            val ids = rows.map(id)
            require(ids.all { it.isNotBlank() && it.length <= 100 } && ids.toSet().size == ids.size) { "备份包含重复或无效ID" }
            return ids.toSet()
        }
        fun amount(value: Long) { require(value in 0..Rules.MAX_CENTS) { "备份包含无效金额" } }
        val employees = unique(s.employees) { it.id }
        val tasks = unique(s.tasks) { it.id }
        unique(s.entries) { it.id }
        val settlements = unique(s.settlements) { it.id }
        s.employees.forEach { Rules.name(it.name); Rules.checkTime(it.createdAt) }
        s.tasks.forEach { Rules.name(it.name); amount(it.wageCents); amount(it.revenueCents) }
        s.entries.forEach {
            require(it.employeeId in employees && it.taskTypeId in tasks) { "备份工作记录缺少员工或任务" }
            Rules.name(it.taskNameSnapshot); amount(it.wageCentsSnapshot); amount(it.revenueCentsSnapshot)
            require(it.quantityTenths in 1..10 && (s.version >= 3 || it.quantityTenths == 10)) { "备份包含无效次数" }
            Rules.checkTime(it.completedAt); Rules.checkTime(it.createdAt); require(it.note.length <= 1000)
        }
        Rules.total(s.entries.map { it.wageAmountCents }); Rules.total(s.entries.map { it.revenueAmountCents })
        require(s.items.map { it.settlementId to it.employeeId }.toSet().size == s.items.size) { "结算明细重复" }
        s.items.forEach { require(it.employeeId in employees && it.settlementId in settlements && it.amountCents >= 0) }
        var end = Rules.beginning
        val normalized = normalize(s)
        normalized.entries.forEach { entry ->
            val owner = s.settlements.find { entry.completedAt > it.periodStart && entry.completedAt <= it.periodEnd }
            require(entry.settlementId == owner?.id) { "工作记录归属的工资单不正确" }
        }
        val assigned = normalized.entries.groupBy { it.settlementId }
        val items = s.items.groupBy { it.settlementId }
        unique(s.adjustments) { it.id }
        require(s.adjustments.all { it.settlementId in settlements && it.previousCents >= 0 && it.updatedCents >= 0 && it.reason.length <= 1000 })
        val changes = s.adjustments.groupBy { it.settlementId }
        s.settlements.sortedBy { it.periodEnd }.forEach { settlement ->
            require(settlement.periodStart == end && settlement.periodEnd > end && settlement.settledAt >= settlement.periodEnd && settlement.settledAt <= s.exportedAt) { "结算区间不连续或时间无效" }
            Rules.checkTime(settlement.periodEnd)
            val sums = (assigned[settlement.id] ?: emptyList()).groupBy { it.employeeId }
                .mapValues { (_, rows) -> Rules.total(rows.map { it.wageAmountCents }) }
            require((items[settlement.id] ?: emptyList()).associate { it.employeeId to it.amountCents } == sums) { "工资单与工作记录金额不一致" }
            val corrections = (changes[settlement.id] ?: emptyList()).sortedBy { it.correctedAt }
            require(settlement.revision == corrections.size + 1) { "工资单修订次数不一致" }
            var lastTotal: Long? = null
            corrections.forEach {
                Rules.checkTime(it.correctedAt)
                require(it.correctedAt >= settlement.settledAt && it.correctedAt <= s.exportedAt)
                require(lastTotal == null || it.previousCents == lastTotal) { "工资单修订金额不连续" }
                lastTotal = it.updatedCents
            }
            require(lastTotal == null || lastTotal == Rules.total(sums.values)) { "工资单修订合计不一致" }
            end = settlement.periodEnd
        }
        val settings = s.settings
        require(settings.id == 1 && settings.settlementDay in 1..31 && settings.backgroundOpacity in 0..40)
        require(settings.primaryColor in 0xFF000000..0xFFFFFFFF && settings.backupHour in 0..23 && settings.backupMinute in 0..59 && settings.smtpPort in 1..65535)
        require(settings.settlementHour in 0..23 && settings.settlementMinute in 0..59)
        require(settings.smtpHost.length <= 253 && settings.sender.length <= 254 && settings.recipient.length <= 254)
        require((settings.backgroundFile == null) == (s.backgroundBase64 == null)) { "备份背景文件缺失" }
        s.backgroundBase64?.let {
            val image = Base64.getDecoder().decode(it)
            require(image.size in 8..8_388_608 && image.take(8) == listOf(137,80,78,71,13,10,26,10).map(Int::toByte)) { "背景图片无效" }
        }
    }
}
