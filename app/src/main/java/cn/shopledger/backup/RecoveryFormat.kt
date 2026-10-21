package cn.shopledger.backup

import cn.shopledger.Rules
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.SecureRandom
import java.text.Normalizer
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable data class QuestionLock(val ids: List<String>, val salt: String, val nonce: String, val wrappedKey: String)
@Serializable data class RecoveryKeyFile(val type: String = "ShopLedgerRecovery", val version: Int = 1,
    val keyId: String, val key: String, val createdAt: Long)
@Serializable data class RecoveryHeader(val keyId: String, val salt: String, val passwordNonce: String,
    val passwordKey: String, val recoveryNonce: String, val recoveryKey: String,
    val passwordRootNonce: String, val passwordRoot: String, val questions: QuestionLock? = null)
data class RecoveredBackup(val snapshot: LedgerSnapshot, val credential: RecoveryKeyFile, val questions: QuestionLock?)

/** Envelope v2: random per-file DEK, wrapped independently by password and recovery root.
 * Questions optionally wrap the root; the mailed .ledgerkey intentionally carries that root.
 * The complete header is authenticated. No answers or passwords are serialized. */
object RecoveryFormat {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val random = SecureRandom()
    private const val ITERATIONS = 600_000
    private const val MAX_HEADER = 16_384
    val questionBank = linkedMapOf(
        "childhood_nickname" to "你小时候最喜欢的昵称是什么？",
        "first_teacher" to "你最记得的一位老师叫什么？",
        "childhood_friend" to "你小时候最要好的朋友叫什么？",
        "first_pet" to "你第一只宠物叫什么？",
        "favorite_place" to "你最想再去的一个地方是哪里？",
        "favorite_dish" to "你最喜欢的一道菜叫什么？",
        "special_phrase" to "你给自己记住的一句特别的话是什么？",
        "favorite_character" to "你最喜欢的一个角色叫什么？",
        "memorable_gift" to "你印象最深的一件礼物是什么？",
        "private_word" to "你为找回密码准备的一个专属词是什么？"
    )
    private fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private fun un64(value: String, size: Int): ByteArray = try {
        require(value.length <= (size + 2) / 3 * 4)
        Base64.getDecoder().decode(value).also { require(it.size == size) }
    } catch (_: IllegalArgumentException) { throw IllegalArgumentException("恢复文件或备份的密钥信息无效") }
    private fun derive(chars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
        return cipher.doFinal(value)
    }
    private fun aad(id: String, kind: String) = "ShopLedger/Recovery/v2/$id/$kind".toByteArray(Charsets.UTF_8)
    private fun validId(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "恢复编号无效" } }
    fun newCredential(now: Long = Rules.now()) = RecoveryKeyFile(keyId = UUID.randomUUID().toString(), key = b64(bytes(32)), createdAt = now)
    fun encodeCredential(file: RecoveryKeyFile): ByteArray {
        validateCredential(file)
        return json.encodeToString(file).toByteArray(Charsets.UTF_8)
    }
    fun decodeCredential(bytes: ByteArray): RecoveryKeyFile {
        require(bytes.size in 1..8192) { "请选择小店账本的.ledgerkey恢复文件" }
        return try { json.decodeFromString<RecoveryKeyFile>(bytes.toString(Charsets.UTF_8)).also(::validateCredential) }
        catch (_: Exception) { throw IllegalArgumentException("恢复文件无效，请从原恢复邮件重新下载.ledgerkey附件") }
    }
    private fun validateCredential(file: RecoveryKeyFile) {
        require(file.type == "ShopLedgerRecovery" && file.version == 1)
        validId(file.keyId); Rules.checkTime(file.createdAt); un64(file.key,32).fill(0)
    }
    fun encodeQuestions(lock: QuestionLock): String = json.encodeToString(lock)
    fun decodeQuestions(text: String): QuestionLock = json.decodeFromString<QuestionLock>(text).also(::validateQuestions)
    private fun validateQuestions(lock: QuestionLock) {
        require(lock.ids.size == 3 && lock.ids.toSet().size == 3 && lock.ids.all { it in questionBank }) { "请选择三个不同的密保问题" }
        un64(lock.salt,16); un64(lock.nonce,12); un64(lock.wrappedKey,48)
    }
    private fun answersKey(answers: List<String>, salt: ByteArray): ByteArray {
        require(answers.size == 3) { "请回答全部三个问题" }
        val normalized = answers.map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") }
        require(normalized.all { it.isNotEmpty() && it.length <= 100 }) { "每个密保答案请填写1至100个字" }
        val chars = json.encodeToString(normalized).toCharArray()
        return try { derive(chars,salt) } finally { chars.fill('\u0000') }
    }
    fun protectQuestions(file: RecoveryKeyFile, ids: List<String>, answers: List<String>): QuestionLock {
        validateCredential(file)
        require(ids.size == 3 && ids.toSet().size == 3 && ids.all { it in questionBank }) { "请选择三个不同的密保问题" }
        val salt = bytes(16); val nonce = bytes(12); val key = answersKey(answers,salt); val root = un64(file.key,32)
        return try { QuestionLock(ids,b64(salt),b64(nonce),b64(crypt(Cipher.ENCRYPT_MODE,key,nonce,aad(file.keyId,"questions/" + json.encodeToString(ids)),root))) }
        finally { key.fill(0); root.fill(0) }
    }
    fun unlockQuestions(id: String, lock: QuestionLock, answers: List<String>): RecoveryKeyFile {
        validId(id); validateQuestions(lock)
        val key = answersKey(answers,un64(lock.salt,16))
        return try {
            val root = crypt(Cipher.DECRYPT_MODE,key,un64(lock.nonce,12),aad(id,"questions/" + json.encodeToString(lock.ids)),un64(lock.wrappedKey,48))
            try { RecoveryKeyFile(keyId=id,key=b64(root),createdAt=Rules.now()) } finally { root.fill(0) }
        } catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("密保答案不正确，请核对三个答案") }
        finally { key.fill(0) }
    }
    fun encrypt(snapshot: LedgerSnapshot, password: CharArray, credential: RecoveryKeyFile, questions: QuestionLock?): ByteArray {
        require(password.size in 6..256) { "备份密码需为6至256位" }
        validateCredential(credential); questions?.let(::validateQuestions)
        val plain = BackupFormat.encodeSnapshot(snapshot)
        val dek = bytes(32); val salt = bytes(16); val pn = bytes(12); val rn = bytes(12); val prn = bytes(12); val dataNonce = bytes(12)
        val passKey = derive(password,salt); val root = un64(credential.key,32)
        try {
            val header = RecoveryHeader(credential.keyId,b64(salt),b64(pn),b64(crypt(Cipher.ENCRYPT_MODE,passKey,pn,aad(credential.keyId,"password"),dek)),
                b64(rn),b64(crypt(Cipher.ENCRYPT_MODE,root,rn,aad(credential.keyId,"recovery"),dek)),
                b64(prn),b64(crypt(Cipher.ENCRYPT_MODE,passKey,prn,aad(credential.keyId,"password-root"),root)),questions)
            val encoded = json.encodeToString(header).toByteArray(Charsets.UTF_8)
            require(encoded.size <= MAX_HEADER)
            val prefix = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.writeInt(0x534c424b); it.writeInt(2); it.writeInt(encoded.size); it.write(encoded); it.write(dataNonce) } }.toByteArray()
            require(prefix.size.toLong() + plain.size + 16 <= BackupFormat.MAX_BYTES) { "账本超过64MB备份容量" }
            return prefix + crypt(Cipher.ENCRYPT_MODE,dek,dataNonce,prefix,plain)
        } finally { dek.fill(0); passKey.fill(0); root.fill(0) }
    }
    private data class Parsed(val header: RecoveryHeader, val nonce: ByteArray, val prefix: ByteArray, val payload: ByteArray)
    private fun parse(bytes: ByteArray): Parsed {
        require(bytes.size in 56..BackupFormat.MAX_BYTES) { "备份文件长度无效" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x534c424b && input.readInt() == 2) { "这是旧版备份，请先用原密码恢复，再导出支持找回的新备份" }
        val length = input.readInt()
        require(length in 1..MAX_HEADER && 24L + length + 16 <= bytes.size) { "备份头部无效" }
        val encoded = ByteArray(length).also(input::readFully)
        val header = try { json.decodeFromString<RecoveryHeader>(encoded.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw IllegalArgumentException("备份头部已损坏") }
        validId(header.keyId); un64(header.salt,16); un64(header.passwordNonce,12); un64(header.passwordKey,48)
        un64(header.recoveryNonce,12); un64(header.recoveryKey,48); header.questions?.let(::validateQuestions)
        un64(header.passwordRootNonce,12); un64(header.passwordRoot,48)
        val nonce = ByteArray(12).also(input::readFully)
        return Parsed(header,nonce,bytes.copyOfRange(0,24+length),bytes.copyOfRange(24+length,bytes.size))
    }
    fun header(bytes: ByteArray): RecoveryHeader = parse(bytes).header
    private fun decrypt(parsed: Parsed, dek: ByteArray): LedgerSnapshot = try {
        BackupFormat.decodeSnapshot(crypt(Cipher.DECRYPT_MODE,dek,parsed.nonce,parsed.prefix,parsed.payload))
    } catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("备份文件已损坏或被修改，当前账本未改动") }
    finally { dek.fill(0) }
    fun decryptPassword(bytes: ByteArray, password: CharArray): LedgerSnapshot {
        val parsed = parse(bytes); val header = parsed.header
        val key = derive(password,un64(header.salt,16))
        val dek = try { crypt(Cipher.DECRYPT_MODE,key,un64(header.passwordNonce,12),aad(header.keyId,"password"),un64(header.passwordKey,48)) }
        catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("备份密码错误或文件已损坏，当前账本未改动") }
        finally { key.fill(0) }
        return decrypt(parsed,dek)
    }
    fun decryptPasswordRecovered(bytes: ByteArray, password: CharArray): RecoveredBackup {
        val parsed = parse(bytes); val header = parsed.header
        val key = derive(password,un64(header.salt,16))
        val root = try { crypt(Cipher.DECRYPT_MODE,key,un64(header.passwordRootNonce,12),aad(header.keyId,"password-root"),un64(header.passwordRoot,48)) }
        catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("备份密码错误或文件已损坏") }
        finally { key.fill(0) }
        return try { decryptCredential(bytes,RecoveryKeyFile(keyId=header.keyId,key=b64(root),createdAt=Rules.now())) }
        finally { root.fill(0) }
    }
    fun decryptCredential(bytes: ByteArray, credential: RecoveryKeyFile): RecoveredBackup {
        validateCredential(credential)
        val parsed = parse(bytes); val header = parsed.header
        require(header.keyId == credential.keyId) { "恢复文件与这份备份不配套，请选择相同恢复编号的邮件附件" }
        val root = un64(credential.key,32)
        val dek = try { crypt(Cipher.DECRYPT_MODE,root,un64(header.recoveryNonce,12),aad(header.keyId,"recovery"),un64(header.recoveryKey,48)) }
        catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("恢复文件不正确或备份已损坏") }
        finally { root.fill(0) }
        return RecoveredBackup(decrypt(parsed,dek),credential,header.questions)
    }
    fun decryptQuestions(bytes: ByteArray, answers: List<String>): RecoveredBackup {
        val header = header(bytes)
        val lock = requireNotNull(header.questions) { "这份备份生成时尚未设置密保，请使用密码或邮箱恢复文件" }
        return decryptCredential(bytes,unlockQuestions(header.keyId,lock,answers))
    }
}
