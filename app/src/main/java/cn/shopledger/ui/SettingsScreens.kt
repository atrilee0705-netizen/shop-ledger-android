package cn.shopledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.*
import cn.shopledger.backup.SettlementScheduler

@Composable fun SettingsScreen(vm: LedgerViewModel, navigate: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var day by remember(settings.settlementDay) { mutableStateOf(settings.settlementDay.toString()) }
    var auto by remember(settings.autoSettlement) { mutableStateOf(settings.autoSettlement) }
    var sendReport by remember(settings.sendSettlementReport) { mutableStateOf(settings.sendSettlementReport) }
    var time by remember(settings.settlementHour, settings.settlementMinute) { mutableStateOf("%02d:%02d".format(settings.settlementHour, settings.settlementMinute)) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("设置", "管好员工、任务和每一份账本")
        LedgerCard {
            Text("每月自动结账", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(day, { if (it.length <= 2) day = it }, label = { Text("每月几号") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(time, { if (it.length <= 5) time = it }, label = { Text("几点出工资单并发送报告，例如12:00") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("到日子自动生成工资单", Modifier.weight(1f)); Switch(auto, { auto = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("工资单生成或修正后发邮件", Modifier.weight(1f)); Switch(sendReport, { sendReport = it }) }
            Text("例如每月15日12:00出单，只算到14日23:59。提前结账后会接着上次截止时间算。邮件需要先在“邮箱与备份”连接邮箱。", style = MaterialTheme.typography.bodySmall)
            Text("月份没有所选日期时用月末。手机省电或关机可能延迟出单和发信，恢复运行后会补办，计算截止日保持不变。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val savedDay = day; val savedTime = time; val savedAuto = auto; val savedSend = sendReport
                vm.action("结账安排已保存") {
                    val value = savedDay.toIntOrNull(); require(value != null && value in 1..31) { "结账日请输入1至31" }
                    val clock = Rules.clock(savedTime)
                    vm.ledger.updateSettings { it.copy(settlementDay = value, settlementHour = clock.hour, settlementMinute = clock.minute, autoSettlement = savedAuto, sendSettlementReport = savedSend) }
                    SettlementScheduler.reschedule(vm.app, vm.ledger.dao.settings()!!)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存结账安排") }
        }
        listOf(Triple("employees", "员工管理", "新增、改名、停用及查询员工"), Triple("tasks", "任务管理", "分别设置每项任务的工资与店铺收入"), Triple("appearance", "外观设置", "主题主色、背景图片和透明度"), Triple("backup", "邮箱与备份", "连接邮箱、每日发送、备份和恢复")).forEach { (route, title, subtitle) ->
            LedgerCard(Modifier.clickable { navigate(route) }) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("›", style = MaterialTheme.typography.headlineSmall) }
            }
        }
        Text("小店账本 ${BuildConfig.VERSION_NAME}\n账本保存在本机 · 业务时间固定为北京时间", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun AppearanceScreen(vm: LedgerViewModel, back: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var hex by remember(settings.primaryColor) { mutableStateOf("%06X".format(settings.primaryColor and 0xFFFFFF)) }
    var opacity by remember(settings.backgroundOpacity) { mutableFloatStateOf(settings.backgroundOpacity.toFloat()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.action("背景图片已保存到本机") { vm.app.backup.chooseBackground(it) } }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("外观设置", "背景适度淡化，金额始终清晰", back)
        LedgerCard {
            Text("主题主色", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(0xFF256C60L, 0xFF315EA8L, 0xFF7753A4L, 0xFF9C5033L, 0xFF37474FL).forEach { value ->
                    Box(Modifier.size(44.dp).background(Color(value.toInt()), CircleShape).clickable { vm.action("") { vm.ledger.updateSettings { it.copy(primaryColor = value) } } })
                }
            }
            OutlinedTextField(hex, { if (it.length <= 6) hex = it.uppercase() }, label = { Text("自定义颜色（6位十六进制）") }, prefix = { Text("#") }, singleLine = true)
            Button(onClick = { vm.action("主题颜色已保存") {
                require(Regex("[0-9A-Fa-f]{6}").matches(hex)) { "颜色格式应为6位十六进制，例如256C60" }
                val value = hex.toLong(16) or 0xFF000000
                vm.ledger.updateSettings { it.copy(primaryColor = value) }
            } }) { Text("应用颜色") }
        }
        LedgerCard {
            Text("背景图片", style = MaterialTheme.typography.titleMedium)
            Text(if (settings.backgroundFile == null) "当前使用默认背景" else "已选择图片，副本保存在App内部目录")
            Button(onClick = { picker.launch("image/*") }) { Text("从相册选择图片") }
            Text("显示强度 ${opacity.toInt()}%")
            Slider(opacity, { opacity = it }, valueRange = 0f..40f, onValueChangeFinished = { val value = opacity.toInt(); vm.action("") { vm.ledger.updateSettings { it.copy(backgroundOpacity = value) } } })
            Text("数值越低，图片越透明。内容卡片保持清晰底色。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.action("已恢复默认背景") { vm.app.backup.clearBackground() } }) { Text("恢复默认背景") }
        }
        Spacer(Modifier.height(16.dp))
    }
}
