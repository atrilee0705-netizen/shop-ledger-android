package cn.shopledger

import cn.shopledger.backup.*
import cn.shopledger.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.Base64

class RecoveryFormatTest {
    private val ids=RecoveryFormat.questionBank.keys.take(3)
    private val answers=listOf("Private nickname","A Teacher","A Friend")
    private val password="123456".toCharArray()
    private fun snapshot():LedgerSnapshot {
        val at=Rules.start(LocalDate.of(2026,10,21))
        return LedgerSnapshot(exportedAt=at,employees=listOf(Employee("e","员工",createdAt=at)),tasks=listOf(TaskType("t","任务",2000,6000)),
            entries=listOf(WorkEntry("w","e","t","任务",2000,6000,at,"工作备注",at)),settlements=emptyList(),items=emptyList(),settings=Settings())
    }
    @Test fun passwordFileAndAllThreeAnswersRecoverTheSameAuthenticatedLedger() {
        val original=snapshot();val root=RecoveryFormat.newCredential();val questions=RecoveryFormat.protectQuestions(root,ids,answers)
        val bytes=RecoveryFormat.encrypt(original,password,root,questions)
        assertEquals(original,BackupFormat.decrypt(bytes,password))
        val portable=RecoveryFormat.decodeCredential(RecoveryFormat.encodeCredential(root))
        assertEquals(original,RecoveryFormat.decryptCredential(bytes,portable).snapshot)
        assertEquals(original,RecoveryFormat.decryptQuestions(bytes,answers).snapshot)
        assertEquals(root.key,RecoveryFormat.decryptPasswordRecovered(bytes,password).credential.key)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Private nickname"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(root.key))
    }
    @Test fun mailedCredentialKeepsWorkingAfterPasswordAndQuestionsChange() {
        val original=snapshot();val root=RecoveryFormat.newCredential()
        val oldLock=RecoveryFormat.protectQuestions(root,ids,answers)
        val old=RecoveryFormat.encrypt(original,password,root,oldLock)
        val newAnswers=listOf("different1","different2","different3")
        val newLock=RecoveryFormat.protectQuestions(root,ids.reversed(),newAnswers)
        val fresh=RecoveryFormat.encrypt(original,"654321".toCharArray(),root,newLock)
        for(bytes in listOf(old,fresh)) assertEquals(original,RecoveryFormat.decryptCredential(bytes,root).snapshot)
        assertEquals(original,RecoveryFormat.decryptQuestions(old,answers).snapshot)
        assertEquals(original,RecoveryFormat.decryptQuestions(fresh,newAnswers).snapshot)
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.decryptQuestions(old,newAnswers)}
        assertThrows(IllegalArgumentException::class.java){BackupFormat.decrypt(old,"654321".toCharArray())}
    }
    @Test fun threeDistinctQuestionsAndCompleteAnswersAreRequiredWithFriendlyNormalization() {
        val root=RecoveryFormat.newCredential()
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.protectQuestions(root,listOf(ids[0],ids[0],ids[2]),answers)}
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.protectQuestions(root,ids,listOf("a","","c"))}
        val lock=RecoveryFormat.protectQuestions(root,ids,listOf("ＡＢＣ"," 王老师 ","New   York"))
        assertEquals(root.key,RecoveryFormat.unlockQuestions(root.keyId,lock,listOf("abc","王老师","new york")).key)
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.unlockQuestions(root.keyId,lock,listOf("abc","王老师","wrong"))}
    }
    @Test fun wrongCredentialTamperedHeaderAndDamagedCiphertextCannotBeUsed() {
        val root=RecoveryFormat.newCredential();val lock=RecoveryFormat.protectQuestions(root,ids,answers)
        val bytes=RecoveryFormat.encrypt(snapshot(),password,root,lock)
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.decryptCredential(bytes,RecoveryFormat.newCredential())}
        val incorrectRoot=root.copy(key=Base64.getEncoder().encodeToString(ByteArray(32)))
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.decryptCredential(bytes,incorrectRoot)}
        val damaged=bytes.clone().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        assertThrows(IllegalArgumentException::class.java){BackupFormat.decrypt(damaged,password)}
        val oldLength=ByteBuffer.wrap(bytes,8,4).int
        val changed=RecoveryFormat.header(bytes).copy(questions=lock.copy(ids=ids.reversed()))
        val encoded=Json{encodeDefaults=true}.encodeToString(changed).toByteArray()
        val altered=ByteBuffer.allocate(24+encoded.size+bytes.size-(24+oldLength))
            .putInt(0x534c424b).putInt(2).putInt(encoded.size).put(encoded).put(bytes,12+oldLength,12).put(bytes,24+oldLength,bytes.size-(24+oldLength)).array()
        assertThrows(IllegalArgumentException::class.java){BackupFormat.decrypt(altered,password)}
    }
    @Test fun legacyBackupsStillUseTheOriginalPasswordAndMalformedFilesAreRejected() {
        val original=snapshot();val legacy=BackupFormat.encrypt(original,password)
        assertEquals(original,BackupFormat.decrypt(legacy,password))
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.decryptCredential(legacy,RecoveryFormat.newCredential())}
        assertThrows(IllegalArgumentException::class.java){RecoveryFormat.decodeCredential("not a recovery file".toByteArray())}
        val oversized=legacy.clone().apply{ByteBuffer.wrap(this,4,8).putInt(2).putInt(Int.MAX_VALUE)}
        assertThrows(IllegalArgumentException::class.java){BackupFormat.decrypt(oversized,password)}
    }
}
