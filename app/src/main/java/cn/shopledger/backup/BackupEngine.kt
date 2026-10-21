package cn.shopledger.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import cn.shopledger.Rules
import cn.shopledger.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

class BackupEngine(private val context: Context, private val ledger: Ledger) {
    private val dao = ledger.dao
    private val secrets = DeviceSecrets()
    private val mailer = Mailer()
    private val mutex = Mutex()
    private val directory = File(context.filesDir, "backups").apply { mkdirs() }
    private val images = File(context.filesDir, "backgrounds").apply { mkdirs() }
    fun background(name: String): File {
        require(Regex("[a-zA-Z0-9-]+\\.png").matches(name))
        return File(images, name)
    }
    private fun backupFile(name: String): File {
        require(Regex("[a-zA-Z0-9-]+\\.ledgerbak").matches(name))
        return File(directory, name)
    }
    suspend fun snapshot(): LedgerSnapshot = ledger.db.withTransaction {
        val settings = dao.settings() ?: Settings()
        LedgerSnapshot(exportedAt = Rules.now(), employees = dao.employees(), tasks = dao.tasks(),
            entries = dao.entries(), settlements = dao.settlements(), items = dao.settlementItems(), settings = settings, adjustments = dao.adjustments(),
            backgroundBase64 = settings.backgroundFile?.let { Base64.getEncoder().encodeToString(background(it).readBytes()) })
    }
    suspend fun setPassword(password: String) = mutex.withLock {
        require(password.length in 6..256) { "备份密码需为6至256位，数字也可以" }
        val encrypted = secrets.encrypt(password)
        ledger.db.withTransaction { dao.saveLocalState((dao.localState() ?: LocalState()).copy(backupSecret = encrypted)) }
        ensureRecoveryLocked()
        RecoveryScheduler.enqueue(context)
    }
    private suspend fun ensureRecoveryLocked(): RecoveryProfile? {
        dao.recovery()?.let { return it }
        if (dao.localState()?.backupSecret == null) return null
        return profileFor(RecoveryFormat.newCredential(),null).also { dao.saveRecovery(it) }
    }
    suspend fun ensureRecovery() = mutex.withLock { ensureRecoveryLocked(); Unit }
    private fun profileFor(file: RecoveryKeyFile, questions: QuestionLock?): RecoveryProfile = RecoveryProfile(
        keyId=file.keyId,keySecret=secrets.encrypt(file.key),createdAt=file.createdAt,questionsJson=questions?.let(RecoveryFormat::encodeQuestions))
    private fun credential(profile: RecoveryProfile) = RecoveryKeyFile(keyId=profile.keyId,key=secrets.decrypt(profile.keySecret),createdAt=profile.createdAt)
    suspend fun setQuestions(ids: List<String>, answers: List<String>) = mutex.withLock {
        val profile = requireNotNull(ensureRecoveryLocked()) { "请先设置备份密码" }
        val lock = RecoveryFormat.protectQuestions(credential(profile),ids,answers)
        dao.saveRecovery(profile.copy(questionsJson=RecoveryFormat.encodeQuestions(lock)))
        dao.saveRecoveryAttempts(RecoveryAttempts())
        RecoveryScheduler.enqueue(context)
    }
    suspend fun setRecoveryAutoEmail(enabled: Boolean) = mutex.withLock {
        val profile = requireNotNull(ensureRecoveryLocked()) { "请先设置备份密码" }
        dao.saveRecovery(profile.copy(autoEmail=enabled))
        if (enabled) RecoveryScheduler.enqueue(context)
    }
    suspend fun resendRecoveryEmail() = mutex.withLock {
        val profile = requireNotNull(ensureRecoveryLocked()) { "请先设置备份密码" }
        dao.saveRecovery(profile.copy(sentAt=null,autoEmail=true))
        RecoveryScheduler.enqueue(context)
    }
    suspend fun exportRecovery(uri: Uri) = mutex.withLock {
        val profile = requireNotNull(ensureRecoveryLocked()) { "请先设置备份密码" }
        val bytes = RecoveryFormat.encodeCredential(credential(profile))
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes); it.flush() }
    }
    private suspend fun readCredential(uri: Uri) = requireNotNull(context.contentResolver.openInputStream(uri)).use { RecoveryFormat.decodeCredential(BackupFormat.readBounded(it,8192)) }
    private suspend fun <T> guardedQuestions(block: suspend () -> T): T {
        val state = dao.recoveryAttempts() ?: RecoveryAttempts()
        require(Rules.now() >= state.blockedUntil) { "密保连续答错，请5分钟后再试；仍可使用恢复文件或密码" }
        return try { block().also { dao.saveRecoveryAttempts(RecoveryAttempts()) } }
        catch (e: IllegalArgumentException) {
            val failures = state.failures + 1
            dao.saveRecoveryAttempts(RecoveryAttempts(failures=if (failures>=5) 0 else failures,blockedUntil=if(failures>=5) Rules.now()+300_000 else 0))
            throw e
        }
    }
    suspend fun resetWithQuestions(answers: List<String>, newPassword: String) = mutex.withLock {
        require(newPassword.length in 6..256) { "新密码至少6位" }
        val profile = requireNotNull(dao.recovery()) { "本机尚未设置找回方式" }
        val lock = requireNotNull(profile.questionsJson?.let(RecoveryFormat::decodeQuestions)) { "本机尚未设置密保" }
        guardedQuestions {
            val recovered = RecoveryFormat.unlockQuestions(profile.keyId,lock,answers)
            require(java.security.MessageDigest.isEqual(recovered.key.toByteArray(),credential(profile).key.toByteArray())) { "密保恢复信息不一致" }
        }
        dao.saveLocalState((dao.localState() ?: LocalState()).copy(backupSecret=secrets.encrypt(newPassword)))
    }
    suspend fun resetWithFile(uri: Uri, newPassword: String) = mutex.withLock {
        require(newPassword.length in 6..256) { "新密码至少6位" }
        val profile = requireNotNull(dao.recovery()) { "请在“从备份文件恢复”中选择备份和配套恢复文件" }
        val recovered = readCredential(uri)
        val saved = credential(profile)
        require(recovered.keyId == saved.keyId && java.security.MessageDigest.isEqual(recovered.key.toByteArray(),saved.key.toByteArray())) { "恢复文件与当前账本不配套" }
        dao.saveLocalState((dao.localState() ?: LocalState()).copy(backupSecret=secrets.encrypt(newPassword)))
    }
    suspend fun sendRecoveryEmail() = mutex.withLock {
        val profile = ensureRecoveryLocked() ?: return@withLock
        if (!profile.autoEmail) return@withLock
        val settings = dao.settings() ?: Settings()
        if (profile.sentAt != null && profile.sentTo == settings.sender) return@withLock
        val secret = dao.localState()?.smtpSecret
        if (settings.sender.isBlank() || secret == null) {
            dao.saveRecovery(profile.copy(lastError="请先连接邮箱，恢复文件随后会自动发给发件邮箱本人")); return@withLock
        }
        val file = File(directory,"recovery-${profile.keyId}.ledgerkey")
        try {
            durableWrite(file,RecoveryFormat.encodeCredential(credential(profile)))
            mailer.send(settings.copy(recipient=settings.sender),secrets.decrypt(secret),file,"recovery-${profile.keyId}-${settings.sender.hashCode().toUInt()}",
                "小店账本 · 请保留这封密码恢复邮件", "附件是配套恢复文件，不包含账目。忘记密码或换手机时，下载.ledgerkey附件，在小店账本中选择“从备份文件恢复→用邮箱恢复文件”。无需记住文件内容。\n恢复编号：${profile.keyId}\n此附件可解开相同编号的新版本备份，请保留在自己的邮箱，并避免转发。", "recovery-${profile.keyId}.ledgerkey")
            dao.saveRecovery(profile.copy(sentAt=Rules.now(),sentTo=settings.sender,lastError=null))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dao.saveRecovery(profile.copy(lastError=safeError(e))); throw e
        } finally { file.delete() }
    }
    suspend fun configure(settings: Settings, smtpPassword: String) = mutex.withLock {
        mailer.validate(settings)
        require(smtpPassword.length <= 1000) { "SMTP授权码过长" }
        val secret = smtpPassword.takeIf { it.isNotBlank() }?.let(secrets::encrypt)
        ledger.db.withTransaction {
            val state = dao.localState() ?: LocalState()
            val old = dao.settings() ?: Settings()
            if (old.sender.isNotBlank() && old.sender.trim() != settings.sender.trim())
                require(secret != null) { "更换发件邮箱时，请填写新邮箱的授权码" }
            val updated = state.copy(smtpSecret = secret ?: state.smtpSecret)
            if (settings.autoBackup) require(updated.smtpSecret != null && updated.backupSecret != null) { "请先设置SMTP授权码和独立备份密码" }
            require(settings.backupHour in 0..23 && settings.backupMinute in 0..59)
            dao.saveLocalState(updated)
            dao.saveSettings(old.copy(smtpHost = settings.smtpHost.trim(), smtpPort = settings.smtpPort,
                sender = settings.sender.trim(), recipient = settings.recipient.trim(),
                backupHour = settings.backupHour, backupMinute = settings.backupMinute, autoBackup = settings.autoBackup))
        }
        BackupScheduler.reschedule(context, settings)
        SettlementScheduler.sendReports(context)
        RecoveryScheduler.enqueue(context)
    }
    suspend fun sendSettlementReports() = mutex.withLock {
        val settings = dao.settings() ?: Settings()
        if (!settings.sendSettlementReport) return@withLock
        for (queued in dao.unsentSettlements()) {
            val secret = dao.localState()?.smtpSecret
            if (secret == null || settings.sender.isBlank()) {
                dao.saveDelivery(SettlementDelivery(queued.id, error = "请先连接邮箱，工资单已保存在手机"))
                return@withLock
            }
            val captured = ledger.db.withTransaction {
                val slip = dao.settlement(queued.id) ?: return@withTransaction null
                val names = dao.employees().associate { it.id to it.name }
                val items = dao.settlementItems().filter { it.settlementId == slip.id }
                val corrections = dao.adjustments().filter { it.settlementId == slip.id }
                val text = buildString {
                    appendLine("工资单 · 第${slip.revision}版${if (slip.revision > 1) "（修正版，请以本版为准）" else ""}")
                    appendLine(Rules.periodText(slip.periodStart, slip.periodEnd))
                    items.forEach { appendLine("${names[it.employeeId] ?: "历史员工"}：${Rules.money(it.amountCents)}") }
                    appendLine("全员工资：${Rules.money(Rules.total(items.map { it.amountCents }))}")
                    corrections.forEach { appendLine("${Rules.timestampFormat.format(java.time.Instant.ofEpochMilli(it.correctedAt))} ${it.reason}；合计由${Rules.money(it.previousCents)}改为${Rules.money(it.updatedCents)}") }
                    appendLine("这是工资核算报告。请按店铺实际情况支付工资。编号：${slip.id}")
                }
                slip to text
            } ?: continue
            try {
                mailer.send(settings, secrets.decrypt(secret), null, "${captured.first.id}-r${captured.first.revision}",
                    "小店账本 · 工资单${if (captured.first.revision > 1) "修正版" else ""} · 截至${Rules.today(captured.first.periodEnd)}", captured.second)
                ledger.db.withTransaction {
                    // A concurrent backfill must keep the newer revision queued.
                    if (dao.settlement(queued.id)?.revision == captured.first.revision)
                        dao.saveDelivery(SettlementDelivery(queued.id, sentAt = Rules.now()))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ledger.db.withTransaction {
                    if (dao.settlement(queued.id) != null) dao.saveDelivery(SettlementDelivery(queued.id, error = safeError(e)))
                }
                throw e
            }
        }
    }
    suspend fun testMail() = mutex.withLock {
        try {
            val settings = dao.settings() ?: Settings()
            val password = requireNotNull(dao.localState()?.smtpSecret) { "请先保存SMTP授权码" }
            mailer.send(settings, secrets.decrypt(password), null, Ledger.uuid())
            ledger.db.withTransaction { dao.saveLocalState((dao.localState() ?: LocalState()).copy(lastError = null)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordError(e)
            throw IllegalStateException(safeError(e))
        }
    }
    private suspend fun encryptedSnapshot(): ByteArray {
        val encrypted = requireNotNull(dao.localState()?.backupSecret) { "请先设置独立备份密码" }
        val password = secrets.decrypt(encrypted).toCharArray()
        val profile = requireNotNull(ensureRecoveryLocked())
        return try { RecoveryFormat.encrypt(snapshot(),password,credential(profile),profile.questionsJson?.let(RecoveryFormat::decodeQuestions)) }
        finally { password.fill('\u0000') }
    }
    suspend fun export(uri: Uri) = mutex.withLock {
        val bytes = encryptedSnapshot()
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入所选文件" }.use { it.write(bytes); it.flush() }
        ledger.db.withTransaction { dao.saveLocalState((dao.localState() ?: LocalState()).copy(lastBackupAt = Rules.now(), lastError = null)) }
    }
    private fun durableWrite(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    suspend fun sendBackup(automatic: Boolean) = mutex.withLock {
        try {
            val settings = dao.settings() ?: Settings()
            if (automatic && !settings.autoBackup) return@withLock
            mailer.validate(settings)
            requireNotNull(dao.localState()?.smtpSecret) { "请先保存SMTP授权码" }
            // An existing attachment is retried byte-for-byte, with a stable Message-ID.
            if (dao.localState()?.pendingFile != null) {
                deliverPending(settings)
                if (!automatic) return@withLock
            }
            val day = Rules.today().toString()
            if (automatic && (dao.localState()?.lastAutoDay ?: "") >= day) return@withLock
            val id = Ledger.uuid()
            val file = backupFile("$id.ledgerbak")
            try {
                durableWrite(file, encryptedSnapshot())
                ledger.db.withTransaction {
                    dao.saveLocalState((dao.localState() ?: LocalState()).copy(lastBackupAt = Rules.now(),
                        pendingFile = file.name, pendingId = id, pendingAutoDay = if (automatic) day else null))
                }
            } catch (e: Exception) { file.delete(); throw e }
            deliverPending(settings)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordError(e)
            throw e
        }
    }
    private suspend fun deliverPending(settings: Settings) {
        val state = requireNotNull(dao.localState())
        val file = backupFile(requireNotNull(state.pendingFile))
        require(file.isFile) { "待发送备份文件丢失，请导出本地账本并重新配置备份" }
        val password = secrets.decrypt(requireNotNull(state.smtpSecret))
        mailer.send(settings, password, file, requireNotNull(state.pendingId))
        ledger.db.withTransaction {
            val current = requireNotNull(dao.localState())
            dao.saveLocalState(current.copy(lastSentAt = Rules.now(), lastError = null, pendingFile = null,
                pendingId = null, pendingAutoDay = null, lastAutoDay = state.pendingAutoDay ?: current.lastAutoDay))
        }
        file.delete()
    }
    suspend fun restore(uri: Uri, password: String) = mutex.withLock {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开备份" }.use { BackupFormat.readBounded(it) }
        val chars = password.toCharArray()
        val (snapshot,recovered) = try {
            val result = if (bytes.size>=8 && bytes[7] == 2.toByte()) RecoveryFormat.decryptPasswordRecovered(bytes,chars) else null
            BackupFormat.normalize(result?.snapshot ?: BackupFormat.decrypt(bytes,chars)) to result
        } finally { chars.fill('\u0000') }
        restoreDecoded(snapshot,recovered?.let { profileFor(it.credential,it.questions) })
    }
    suspend fun restoreWithFile(uri: Uri, recoveryUri: Uri, newPassword: String) = mutex.withLock {
        require(newPassword.length in 6..256) { "新密码至少6位" }
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupFormat.readBounded(it) }
        val recovered = RecoveryFormat.decryptCredential(bytes,readCredential(recoveryUri))
        restoreDecoded(BackupFormat.normalize(recovered.snapshot),profileFor(recovered.credential,recovered.questions),newPassword)
    }
    suspend fun restoreWithQuestions(uri: Uri, answers: List<String>, newPassword: String) = mutex.withLock {
        require(newPassword.length in 6..256) { "新密码至少6位" }
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupFormat.readBounded(it) }
        val recovered = guardedQuestions { RecoveryFormat.decryptQuestions(bytes,answers) }
        restoreDecoded(BackupFormat.normalize(recovered.snapshot),profileFor(recovered.credential,recovered.questions),newPassword)
    }
    suspend fun backupQuestions(uri: Uri): List<String> {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupFormat.readBounded(it) }
        return RecoveryFormat.header(bytes).questions?.ids ?: throw IllegalArgumentException("这份备份生成时未设置密保，请选密码或恢复文件")
    }
    private suspend fun restoreDecoded(snapshot: LedgerSnapshot, recoveredProfile: RecoveryProfile? = null, newPassword: String? = null) {
        var newImage: File? = null
        val oldSettings = dao.settings()
        val oldPending = dao.localState()?.pendingFile
        try {
            snapshot.backgroundBase64?.let {
                val imageBytes = Base64.getDecoder().decode(it)
                checkImage(imageBytes)
                newImage = background("${Ledger.uuid()}.png").also { image -> durableWrite(image, imageBytes) }
            }
            ledger.db.withTransaction {
                dao.clearItems(); dao.clearSettlements(); dao.clearEntries(); dao.clearTasks(); dao.clearEmployees()
                dao.insertEmployees(snapshot.employees); dao.insertTasks(snapshot.tasks); dao.insertEntries(snapshot.entries)
                dao.insertSettlements(snapshot.settlements); dao.insertItems(snapshot.items)
                dao.insertAdjustments(snapshot.adjustments)
                snapshot.settlements.forEach { dao.saveDelivery(SettlementDelivery(it.id, sentAt = 0)) }
                dao.saveSettings(snapshot.settings.copy(backgroundFile = newImage?.name, autoBackup = false, autoSettlement = false))
                dao.clearRecovery()
                recoveredProfile?.let { dao.saveRecovery(it) }
                dao.saveRecoveryAttempts(RecoveryAttempts())
                dao.saveLocalState(LocalState(backupSecret=newPassword?.let(secrets::encrypt)))
            }
        } catch (e: Exception) { newImage?.delete(); throw e }
        BackupScheduler.cancel(context)
        SettlementScheduler.reschedule(context, snapshot.settings.copy(autoSettlement = false))
        oldSettings?.backgroundFile?.let { background(it).delete() }
        oldPending?.let { backupFile(it).delete() }
    }
    suspend fun chooseBackground(uri: Uri) = mutex.withLock {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupFormat.readBounded(it, 20 * 1024 * 1024) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "请选择有效且不超过1亿像素的图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "无法读取图片" }
        val file = background("${Ledger.uuid()}.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.fd.sync() }
            require(file.length() <= 8_388_608) { "背景图片过大，请选择更小的图片" }
            val previous = dao.settings()?.backgroundFile
            ledger.updateSettings { it.copy(backgroundFile = file.name) }
            previous?.let { background(it).delete() }
        } catch (e: Exception) { file.delete(); throw e }
        finally { bitmap.recycle() }
    }
    suspend fun clearBackground() = mutex.withLock {
        val previous = dao.settings()?.backgroundFile
        ledger.updateSettings { it.copy(backgroundFile = null) }
        previous?.let { background(it).delete() }
    }
    private fun checkImage(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) { "备份背景尺寸无效" }
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).recycle()
    }
    private suspend fun recordError(error: Exception) = ledger.db.withTransaction {
        dao.saveLocalState((dao.localState() ?: LocalState()).copy(lastError = safeError(error)))
    }
    companion object {
        fun safeError(e: Exception): String = when (e) {
            is jakarta.mail.AuthenticationFailedException -> "邮箱连接失败：请检查邮箱地址和授权码，并按“授权码怎么获取”重新操作"
            is jakarta.mail.MessagingException -> "邮件暂未发出：请检查网络和邮箱配置；系统会保留并重试。Gmail需要能连接Google的网络"
            is java.security.GeneralSecurityException -> "无法读取本机密钥，请重新设置授权码和备份密码"
            is IllegalArgumentException, is IllegalStateException -> e.message?.take(180) ?: "备份配置或文件无效"
            is java.io.IOException -> "文件或网络读写失败，请检查可用空间和网络"
            else -> "备份执行失败（${e.javaClass.simpleName}）"
        }
    }
}
