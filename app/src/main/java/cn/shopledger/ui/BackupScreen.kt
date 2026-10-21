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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cn.shopledger.*
import cn.shopledger.backup.BackupScheduler
import cn.shopledger.backup.MailProviders
import java.time.Instant

@Composable fun BackupScreen(vm: LedgerViewModel, back: () -> Unit, openRecovery: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.localState.collectAsStateWithLifecycle()
    val workFlow = remember { WorkManager.getInstance(vm.app).getWorkInfosByTagFlow("ledger-backup") }
    val jobs by workFlow.collectAsStateWithLifecycle(emptyList())
    var host by remember(settings.smtpHost) { mutableStateOf(settings.smtpHost) }
    var port by remember(settings.smtpPort) { mutableStateOf(settings.smtpPort.toString()) }
    var sender by remember(settings.sender) { mutableStateOf(settings.sender) }
    var recipient by remember(settings.recipient) { mutableStateOf(settings.recipient) }
    var smtpPassword by remember { mutableStateOf("") }
    var time by remember(settings.backupHour, settings.backupMinute) { mutableStateOf("%02d:%02d".format(settings.backupHour, settings.backupMinute)) }
    var auto by remember(settings.autoBackup) { mutableStateOf(settings.autoBackup) }
    var passwordDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val verifyPasswordChange = rememberDeviceVerification({passwordDialog=true},{message->vm.action(""){throw IllegalArgumentException(message)}})
    val provider = MailProviders.find(sender)
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(provider?.host) { if (provider != null && (!advanced || host.isBlank())) { host = provider.host; port = "465" } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { busy = true; vm.action("完整加密备份已导出", onComplete = { busy = false }) { vm.app.backup.export(it) } }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    fun stamp(value: Long?): String = value?.let { Rules.timestampFormat.format(Instant.ofEpochMilli(it)) } ?: "暂无"
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("邮箱与备份", "工资单发邮件 · 完整账本留备份", back)
        LedgerCard {
            Text("备份状态", style = MaterialTheme.typography.titleMedium)
            Text("上次生成备份：${stamp(state.lastBackupAt)}\n上次成功发送：${stamp(state.lastSentAt)}\n待发送附件：${if (state.pendingFile != null) "有，失败后继续重试" else "无"}", style = MaterialTheme.typography.bodyMedium)
            val running = jobs.any { it.state == WorkInfo.State.RUNNING }
            val waiting = jobs.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            Text("后台任务：${if (running) "正在执行" else if (waiting) "已安排，等待时间或网络" else "未安排"}", style = MaterialTheme.typography.bodySmall)
            state.lastError?.let { Text("最近错误：$it", color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LedgerCard {
            Text("独立备份密码", style = MaterialTheme.typography.titleMedium)
            Text(if (state.backupSecret != null) "已设置，密码安全保存在本机" else "完整账本备份需要这个密码；只发工资报告可以不设置")
            Text("密码至少6位，纯数字也可以。支持手机验证重设、三个密保问题及邮箱恢复文件找回。请先设置找回方式，再导出新备份。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { if(state.backupSecret==null) passwordDialog=true else verifyPasswordChange() }) { Text(if (state.backupSecret == null) "设置备份密码" else "验证手机并更改备份密码") }
            Button(enabled=!busy,onClick=openRecovery,modifier=Modifier.fillMaxWidth()){Text("忘记密码 / 密保与找回")}
        }
        LedgerCard {
            Text("连接你的邮箱", style = MaterialTheme.typography.titleMedium)
            MailField(sender, { sender = it }, "你的邮箱地址", KeyboardType.Email)
            Text(provider?.let { "已识别${it.name}，发信设置会自动填好。" } ?: "支持QQ、163、126和Gmail自动配置。其他邮箱请展开高级设置。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(smtpPassword, { if (it.length <= 1000) smtpPassword = it }, label = { Text(if (state.smtpSecret == null) "邮箱授权码 / Gmail应用专用密码" else "邮箱授权码（不更换可留空）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Text("授权码是邮箱给这个App的专用密码。先在邮箱里生成，再粘贴到这里。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { help = true }) { Text("授权码怎么获取？点这里看步骤") }
            MailField(recipient, { recipient = it }, "发给哪个邮箱（留空发给自己）", KeyboardType.Email)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置（一般不用改）") }
            if (advanced) {
                MailField(host, { host = it }, "发信服务器（SMTP）")
                MailField(port, { port = it }, "加密端口，通常465", KeyboardType.Number)
                Text("始终使用加密连接。当前支持连接时即加密的端口。", style = MaterialTheme.typography.bodySmall)
            }
            MailField(time, { time = it }, "每天几点发完整备份，例如22:00", KeyboardType.Text)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("每天自动发送完整备份", Modifier.weight(1f)); Switch(auto, { auto = it }, enabled = !busy) }
            Text("工资报告按“设置”里的结账时间发送。这里的每日备份另有独立时间。", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = {
                val capturedHost = if (!advanced && provider != null) provider.host else host
                val capturedPort = if (!advanced && provider != null) "465" else port
                val capturedSender = sender.trim(); val capturedRecipient = recipient.trim().ifBlank { capturedSender }
                val capturedTime = time; val capturedPassword = smtpPassword; val capturedAuto = auto
                busy = true
                vm.action("邮箱配置与后台计划已保存", onSuccess = { smtpPassword = "" }, onComplete = { busy = false }) {
                    val clock = Rules.clock(capturedTime)
                    val authorization = if (MailProviders.find(capturedSender)?.name == "Gmail") capturedPassword.replace(" ", "") else capturedPassword.trim()
                    vm.app.backup.configure(settings.copy(smtpHost = capturedHost, smtpPort = capturedPort.toIntOrNull() ?: 0,
                        sender = capturedSender, recipient = capturedRecipient, backupHour = clock.hour, backupMinute = clock.minute, autoBackup = capturedAuto), authorization)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存邮箱与计划") }
            OutlinedButton(enabled = !busy && state.smtpSecret != null, onClick = {
                busy = true; vm.action("测试邮件已提交，请查看收件箱", onComplete = { busy = false }) { vm.app.backup.testMail() }
            }, modifier = Modifier.fillMaxWidth()) { Text("测试发送（使用已保存配置）") }
            Button(enabled = !busy && state.smtpSecret != null && state.backupSecret != null, onClick = {
                BackupScheduler.sendNow(vm.app)
                vm.action("已加入发送队列，请查看真实发送状态") { }
            }, modifier = Modifier.fillMaxWidth()) { Text("立即备份并发送") }
            Text("手机省电或无网络时可能晚些发送，会继续重试。强制停止App后请重新打开。极少数意外断电情况可能收到重复邮件，可按编号核对。", style = MaterialTheme.typography.bodySmall)
        }
        LedgerCard {
            Text("文件导出与恢复", style = MaterialTheme.typography.titleMedium)
            Text("每份 .ledgerbak 均包含全部员工、任务、工作记录、结算、外观设置与背景图片。SMTP授权码和本机发送状态不会导出。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && state.backupSecret != null, onClick = { export.launch("ledger-${Rules.today()}.ledgerbak") }, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份文件") }
            OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("从备份文件恢复") }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (help) {
        var helpProvider by remember { mutableStateOf(if (provider?.name == "Gmail") "Gmail" else if (provider?.name?.contains("163") == true || provider?.name?.contains("126") == true) "163/126" else "QQ") }
        AlertDialog(onDismissRequest = { help = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("授权码怎么获取") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("QQ", "163/126", "Gmail").forEach { name -> FilterChip(helpProvider == name, { helpProvider = name }, { Text(name) }) }
            }
            if (helpProvider == "QQ") {
            Text("QQ邮箱：\n1. 用电脑打开mail.qq.com并登录。\n2. 点“设置”→“账户”，找到“IMAP/SMTP服务”并开启。\n3. 按提示验证身份，生成授权码。\n4. 复制到本App的“邮箱授权码”栏。服务已开启时，可在管理服务中生成新码。")
            TextButton(onClick = { uriHandler.openUri("https://consumer.huawei.com/cn/support/content/zh-cn16108643/") }) { Text("查看QQ邮箱操作图解") }
            }
            if (helpProvider == "163/126") {
            Text("163 / 126邮箱：\n1. 登录对应的网页版邮箱。\n2. 点“设置”→“POP/SMTP/IMAP”。\n3. 开启“IMAP/SMTP服务”，按提示完成验证。\n4. 将生成的“客户端授权密码”复制到本App。")
            TextButton(onClick = { uriHandler.openUri("https://consumer.huawei.com/cn/support/content/zh-cn15872099/") }) { Text("查看163邮箱操作图解") }
            }
            if (helpProvider == "Gmail") {
            Text("Gmail：\n1. 进入Google账号→“安全性”，开启“两步验证”。\n2. 打开“应用专用密码”，新建一个名为“小店账本”的密码。\n3. 将生成的16位密码复制到本App，空格可一起粘贴。\n部分工作、学校或高级保护账号没有这个选项。发送时手机需要能连接Google邮件服务。")
            TextButton(onClick = { uriHandler.openUri("https://myaccount.google.com/apppasswords") }) { Text("打开Google应用专用密码") }
            TextButton(onClick = { uriHandler.openUri("https://support.google.com/accounts/answer/185833?hl=zh-Hans") }) { Text("查看Google官方说明") }
            }
            Text("邮箱的页面名称可能随版本变化。授权码只填在本App里，无需发给任何人。邮箱登录密码和独立备份密码分别用于登录邮箱、恢复备份，请各自保管。")
        }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
    }
    if (passwordDialog) {
        var password by remember { mutableStateOf("") }
        var repeated by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { passwordDialog = false }, title = { Text("设置独立备份密码") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("设置后会生成配套恢复文件，连接邮箱后自动发给发件邮箱本人。可再从题库选三个密保问题。旧版文件仍需原密码，新版备份还可用配套密保或恢复文件打开。")
                SecretField(password, { password = it }, "密码（至少6位，数字也可以）")
                SecretField(repeated, { repeated = it }, "再次输入密码")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            if (password.length !in 6..256 || password != repeated) error = "密码至少6位，且两次输入要一致"
            else {
                val captured = password; password = ""; repeated = ""; passwordDialog = false; busy = true
                vm.action("备份密码已安全保存，请妥善保管", onComplete = { busy = false }) { vm.app.backup.setPassword(captured) }
            }
        }) { Text("我已知晓，保存密码") } }, dismissButton = { TextButton(onClick = { passwordDialog = false }) { Text("取消") } })
    }
    restoreUri?.let { uri -> RestoreBackupDialog(vm,uri){restoreUri=null} }
}
@Composable private fun MailField(value: String, change: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, { if (it.length <= 254) change(it) }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}
@Composable private fun SecretField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, { if (it.length <= 256) change(it) }, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
