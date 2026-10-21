package cn.shopledger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.*
import cn.shopledger.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable fun RecoveryScreen(vm: LedgerViewModel, back: () -> Unit) {
    val profile by vm.recovery.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val local by vm.localState.collectAsStateWithLifecycle()
    val ids = profile?.questionsJson?.let { RecoveryFormat.decodeQuestions(it).ids }.orEmpty()
    var busy by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<String?>(null) }
    var authorizedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val verify = rememberDeviceVerification({ authorizedAction?.invoke(); authorizedAction = null }, { message -> vm.action("") { throw IllegalArgumentException(message) } })
    fun verified(action: () -> Unit) { authorizedAction = action; verify() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { busy = true; vm.action("恢复文件已保存，无需记住文件内容",onComplete={busy=false}) { vm.app.backup.exportRecovery(it) } }
    }
    var resetFile by remember { mutableStateOf<Uri?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri!=null) { resetFile=uri; form="file" } }
    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PageTitle("密保与密码找回","忘记密码也有办法 · 无需自建服务器",back)
        LedgerCard {
            Text("原手机还能使用",style=MaterialTheme.typography.titleMedium)
            Text("验证指纹或手机锁屏密码后，直接设置新的备份密码。本机账目不会清空。",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy && local.backupSecret!=null,onClick={ verified { form="device" } },modifier=Modifier.fillMaxWidth()) { Text("指纹 / 锁屏验证后重设") }
        }
        LedgerCard {
            Text("选择三个密保问题",style=MaterialTheme.typography.titleMedium)
            if (ids.isEmpty()) Text("尚未设置。从题库选三个不同的问题，答案由你填写。")
            else ids.forEachIndexed { index,id -> Text("${index+1}. ${RecoveryFormat.questionBank[id]}",style=MaterialTheme.typography.bodySmall) }
            Text("三个答案全部正确才能找回。英文不分大小写，首尾空格会忽略。请选自己记得、别人不容易猜到的答案。",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=!busy && local.backupSecret!=null,onClick={ if(ids.isEmpty()) form="setup" else verified { form="setup" } },modifier=Modifier.fillMaxWidth()) { Text(if(ids.isEmpty()) "从题库挑选三个问题" else "重新设置三个问题") }
            OutlinedButton(enabled=!busy && ids.size==3,onClick={form="questions"},modifier=Modifier.fillMaxWidth()) { Text("回答密保问题重设密码") }
            Text("后来设置或更换的问题，只会用于之后生成的新备份。旧备份仍对应生成时的问题。",style=MaterialTheme.typography.bodySmall)
        }
        LedgerCard {
            Text("恢复文件自动留在邮箱",style=MaterialTheme.typography.titleMedium)
            Text("发送到发件邮箱本人：${settings.sender.ifBlank { "请先连接邮箱" }}")
            Text(when {
                profile==null -> "先设置备份密码，系统会生成恢复文件。"
                profile?.lastError!=null -> "待发送：${profile?.lastError}"
                profile?.sentAt!=null -> "已提交邮件：${Rules.timestampFormat.format(Instant.ofEpochMilli(profile!!.sentAt!!))}\n收件邮箱：${profile?.sentTo}"
                else -> "已生成，等待自动发送"
            },style=MaterialTheme.typography.bodySmall)
            Text("邮件主题为“小店账本 · 请保留这封密码恢复邮件”。无需记恢复码，保留.ledgerkey附件即可。它能解开配套的新备份，请留在自己的邮箱。",style=MaterialTheme.typography.bodySmall)
            profile?.let { saved ->
                Text("恢复编号：${saved.keyId.take(8)}",style=MaterialTheme.typography.labelSmall)
                Row { Text("自动保存到邮箱",Modifier.weight(1f)); Switch(saved.autoEmail,{ enabled ->
                    vm.action("恢复邮件安排已更新") { vm.app.backup.setRecoveryAutoEmail(enabled) }
                },enabled=!busy) }
            }
            OutlinedButton(enabled=!busy && profile!=null,onClick={vm.action("恢复文件已加入邮件队列") { vm.app.backup.resendRecoveryEmail() }},modifier=Modifier.fillMaxWidth()) { Text("重新发送恢复文件到本人邮箱") }
            OutlinedButton(enabled=!busy && profile!=null,onClick={verified { export.launch("ledger-recovery-${profile?.keyId?.take(8)}.ledgerkey") }},modifier=Modifier.fillMaxWidth()) { Text("另存一份恢复文件") }
            TextButton(enabled=!busy && profile!=null,onClick={pickFile.launch(arrayOf("*/*"))}) { Text("用邮箱恢复文件重设本机备份密码") }
            Text("换手机时：先下载账本备份和恢复文件，再到“从备份文件恢复”选择“邮箱文件”。",style=MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
    if (form=="setup") QuestionsSetupDialog(ids,{form=null}) { selected,answers ->
        form=null; busy=true
        vm.action("三个密保问题已设置，请重新导出一份备份",onComplete={busy=false}) { vm.app.backup.setQuestions(selected,answers) }
    }
    if (form in listOf("device","questions","file")) {
        val mode = form
        ResetPasswordDialog(if(mode=="questions") ids else emptyList(),{form=null}) { answers,newPassword ->
            form=null; busy=true
            vm.action("备份密码已重设，本机账目保留；新备份使用新密码",onComplete={busy=false}) {
                when(mode) {
                    "questions" -> vm.app.backup.resetWithQuestions(answers,newPassword)
                    "file" -> vm.app.backup.resetWithFile(requireNotNull(resetFile),newPassword)
                    else -> vm.app.backup.setPassword(newPassword)
                }
            }
        }
    }
}

@Composable private fun QuestionsSetupDialog(existing: List<String>,dismiss:()->Unit,save:(List<String>,List<String>)->Unit) {
    val choices = remember { RecoveryFormat.questionBank.keys.toList() }
    val selected = remember { mutableStateListOf<String>().apply { addAll(if(existing.size==3) existing else choices.take(3)) } }
    val answers = remember { mutableStateListOf("","","") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=dismiss,title={Text("从题库选三个问题")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("点每个问题可以换题，三个问题不能重复。")
            repeat(3) { index ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={menu=true},modifier=Modifier.fillMaxWidth()) { Text("${index+1}. ${RecoveryFormat.questionBank[selected[index]]}") }
                    DropdownMenu(menu,{menu=false}) {
                        choices.filter { it==selected[index] || it !in selected }.forEach { id ->
                            DropdownMenuItem(text={Text(RecoveryFormat.questionBank.getValue(id))},onClick={ selected[index]=id; answers[index]=""; menu=false })
                        }
                    }
                }
                RecoverySecretField(answers[index],{answers[index]=it},"第${index+1}题答案")
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={ if(answers.any { it.isBlank() }) error="请填好三个答案" else save(selected.toList(),answers.toList()) }) {Text("保存三个问题")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
@Composable fun ResetPasswordDialog(ids:List<String>,dismiss:()->Unit,save:(List<String>,String)->Unit) {
    val answers = remember { mutableStateListOf("","","") }
    var password by remember { mutableStateOf("") }; var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=dismiss,title={Text("设置新的备份密码")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            QuestionsAnswerFields(ids,answers)
            RecoverySecretField(password,{password=it},"新密码（至少6位）")
            RecoverySecretField(repeat,{repeat=it},"再输入一次新密码")
            Text("本机账目保留。旧版文件仍用原密码，新版备份还可用配套恢复文件或密保找回。",style=MaterialTheme.typography.bodySmall)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={
        error=when { ids.isNotEmpty() && answers.any {it.isBlank()} -> "请回答三个密保问题"; password.length !in 6..256 || password!=repeat -> "新密码至少6位，且两次输入一致"; else -> null }
        if(error==null) save(answers.toList(),password)
    }){Text("保存新密码")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
@Composable fun QuestionsAnswerFields(ids:List<String>,answers:MutableList<String>) {
    ids.forEachIndexed { index,id ->
        Text("${index+1}. ${RecoveryFormat.questionBank[id]}",style=MaterialTheme.typography.bodyMedium)
        RecoverySecretField(answers[index],{answers[index]=it},"第${index+1}题答案")
    }
}
@Composable fun RecoverySecretField(value:String,change:(String)->Unit,label:String) {
    OutlinedTextField(value,{if(it.length<=256)change(it)},label={Text(label)},singleLine=true,visualTransformation=PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
}

@Composable fun RestoreBackupDialog(vm:LedgerViewModel,uri:Uri,dismiss:()->Unit) {
    var method by remember {mutableIntStateOf(0)}
    var password by remember {mutableStateOf("")}; var newPassword by remember {mutableStateOf("")}; var repeated by remember {mutableStateOf("")}
    var file by remember {mutableStateOf<Uri?>(null)}; var ids by remember {mutableStateOf<List<String>>(emptyList())}
    var questionError by remember {mutableStateOf<String?>(null)}; var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val answers=remember {mutableStateListOf("","","")}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {file=it}
    LaunchedEffect(uri) {
        try {ids=withContext(Dispatchers.IO){vm.app.backup.backupQuestions(uri)}}
        catch(e:Exception){questionError=e.message ?: "无法读取备份的密保信息"}
    }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("恢复将覆盖当前账本")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("员工、任务、账目和工资单将替换为备份内容。建议先导出当前账本；验证失败不会改动账目。",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("密码","邮箱文件","密保").forEachIndexed{i,name->FilterChip(method==i,{method=i},{Text(name)})}}
            when(method){
                0 -> RecoverySecretField(password,{password=it},"这份备份的密码")
                1 -> {Text("先从恢复邮件下载.ledgerkey附件，再选择它。");OutlinedButton(onClick={pick.launch(arrayOf("*/*"))}){Text(if(file==null)"选择恢复文件" else "恢复文件已选择，可重新选")}}
                2 -> if(ids.size==3) QuestionsAnswerFields(ids,answers) else Text(questionError ?: "正在读取密保问题",color=MaterialTheme.colorScheme.error)
            }
            if(method!=0){RecoverySecretField(newPassword,{newPassword=it},"恢复后使用的新密码（至少6位）");RecoverySecretField(repeated,{repeated=it},"再输入一次新密码")}
            Text("恢复后自动结账和每日备份关闭。邮箱授权码需要重新配置。",style=MaterialTheme.typography.bodySmall)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(enabled=!busy,onClick={
        error=when{
            method==0 && password.isBlank() -> "请输入备份密码"
            method==1 && file==null -> "请选择邮箱中的恢复文件"
            method==2 && (ids.size!=3 || answers.any{it.isBlank()}) -> "请回答三个密保问题"
            method!=0 && (newPassword.length !in 6..256 || newPassword!=repeated) -> "新密码至少6位，且两次输入一致"
            else -> null
        }
        if(error==null){
            val pass=password;val fresh=newPassword;val selectedFile=file;val response=answers.toList();val selected=method;busy=true
            vm.action("账本已恢复，请检查账目并重新连接邮箱",onSuccess=dismiss,onComplete={busy=false}){
                when(selected){0->vm.app.backup.restore(uri,pass);1->vm.app.backup.restoreWithFile(uri,requireNotNull(selectedFile),fresh);else->vm.app.backup.restoreWithQuestions(uri,response,fresh)}
            }
        }
    }){Text("确认覆盖并恢复")}},dismissButton={TextButton(enabled=!busy,onClick=dismiss){Text("取消")}})
}
