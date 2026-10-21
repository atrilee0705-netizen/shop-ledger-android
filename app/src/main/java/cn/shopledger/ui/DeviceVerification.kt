package cn.shopledger.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Uses the system's verification UI; the app never asks for or receives the lock PIN. */
@Suppress("DEPRECATION")
@Composable fun rememberDeviceVerification(verified: () -> Unit, error: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val onVerified by rememberUpdatedState(verified)
    val onError by rememberUpdatedState(error)
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onVerified()
    }
    val fallback: () -> Unit = {
        val manager = context.getSystemService(KeyguardManager::class.java)
        val intent = manager.createConfirmDeviceCredentialIntent("验证手机身份", "验证后可重设备份密码或修改找回方式")
        if (intent != null) launcher.launch(intent) else onError("请先在手机设置中开启锁屏密码，再使用手机验证；也可选择密保或恢复文件")
    }
    DisposableEffect(Unit) { onDispose { signal?.cancel() } }
    return {
        val manager = context.getSystemService(KeyguardManager::class.java)
        if (!manager.isDeviceSecure) onError("请先开启手机锁屏密码；也可选择密保或邮箱恢复文件")
        else if (Build.VERSION.SDK_INT >= 28) {
            try {
                signal?.cancel()
                val cancellation = CancellationSignal(); signal = cancellation
                val builder = BiometricPrompt.Builder(context).setTitle("验证后重设备份密码").setSubtitle("使用指纹或手机锁屏密码")
                if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                else if (Build.VERSION.SDK_INT >= 29) builder.setDeviceCredentialAllowed(true)
                else builder.setNegativeButton("使用锁屏密码",context.mainExecutor) { _, _ -> fallback() }
                builder.build().authenticate(cancellation,context.mainExecutor,object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { signal = null; onVerified() }
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        signal = null
                        if (code !in listOf(5,10,13)) fallback()
                    }
                })
            } catch (_: Exception) { fallback() }
        } else fallback()
    }
}
