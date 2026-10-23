package cn.shopledger.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View

/** Short detent feedback, respecting touch-feedback settings and motor support. */
class WheelHaptics(private val view: View) {
    private var lastTick = -100L
    @Suppress("DEPRECATION")
    fun tick(strength: Int) {
        if (strength !in 1..3 || !view.isHapticFeedbackEnabled || !view.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 40) return
        try {
            val context = view.context
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator == null || !vibrator.hasVibrator()) return
            val effect = if (Build.VERSION.SDK_INT >= 30 && vibrator.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK).all { it }) {
                VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK,
                    when (strength) { 1 -> .25f; 2 -> .55f; else -> 1f }).compose()
            } else {
                val amplitude = if (vibrator.hasAmplitudeControl()) when (strength) { 1 -> 45; 2 -> 110; else -> 200 } else VibrationEffect.DEFAULT_AMPLITUDE
                VibrationEffect.createOneShot(when (strength) { 1 -> 8L; 2 -> 12L; else -> 18L }, amplitude)
            }
            lastTick = now
            if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
            else vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        } catch (_: SecurityException) {
            // A restricted device must still allow normal recording without feedback.
        } catch (_: IllegalArgumentException) {
            // Some vendor motors advertise effects they cannot actually play.
        }
    }
}
