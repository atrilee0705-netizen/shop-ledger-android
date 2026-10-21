package cn.shopledger.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shopledger.LedgerViewModel
import cn.shopledger.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun LedgerRoot(vm: LedgerViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val seed = Color(settings.primaryColor.toInt())
    var color = seed
    while (color.luminance() > .18f) color = lerp(color, Color.Black, .08f)
    val scheme = lightColorScheme(primary = color, onPrimary = readableColor(color),
        primaryContainer = color.copy(alpha = .13f).compositeOver(Color.White), onPrimaryContainer = Color(0xFF152B26),
        secondary = color, onSecondary = readableColor(color),
        secondaryContainer = color.copy(alpha = .13f).compositeOver(Color.White), onSecondaryContainer = Color(0xFF152B26),
        surface = Color.White, background = Color(0xFFF3F6F7))
    var route by rememberSaveable { mutableStateOf("ledger") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailDate by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    val background by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, settings.backgroundFile) {
        value = withContext(Dispatchers.IO) {
            settings.backgroundFile?.let { runCatching { BitmapFactory.decodeFile(vm.app.backup.background(it).absolutePath)?.asImageBitmap() }.getOrNull() }
        }
    }
    LaunchedEffect(vm) { for (event in vm.events) snack.showSnackbar(event) }
    BackHandler(detailId != null || route !in listOf("ledger", "settle", "settings")) {
        if (detailId != null) detailId = null else route = "settings"
    }
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(scheme.background)) {
            background?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = settings.backgroundOpacity / 100f) }
            Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) }, bottomBar = {
                if (detailId == null) NavigationBar(containerColor = scheme.surface) {
                    listOf(Triple("ledger", "记账", Icons.Outlined.EditNote), Triple("settle", "工资结算", Icons.Outlined.AccountBalanceWallet), Triple("settings", "设置", Icons.Outlined.Settings)).forEach { (key, title, icon) ->
                        NavigationBarItem(selected = route == key || (key == "settings" && route !in listOf("ledger", "settle")),
                            onClick = { route = key }, icon = { Icon(icon, title) }, label = { Text(title) })
                    }
                }
            }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {
                    val id = detailId
                    if (id != null) DetailScreen(vm, id, LocalDate.parse(detailDate)) { detailId = null }
                    else when (route) {
                        "ledger" -> DailyScreen(vm, openManagement = { route = it }, openDetail = { employee, date -> detailId = employee; detailDate = date.toString() })
                        "settle" -> SettlementScreen(vm)
                        "settings" -> SettingsScreen(vm) { route = it }
                        "employees" -> EmployeeScreen(vm) { route = "settings" }
                        "tasks" -> TaskScreen(vm) { route = "settings" }
                        "appearance" -> AppearanceScreen(vm) { route = "settings" }
                        "backup" -> BackupScreen(vm,back={route="settings"},openRecovery={route="recovery"})
                        "recovery" -> RecoveryScreen(vm){route="backup"}
                    }
                }
            }
        }
    }
}
private fun readableColor(color: Color): Color = if (color.luminance() > .42f) Color.Black else Color.White
