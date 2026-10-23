package cn.shopledger

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.Vibrator
import android.provider.Settings
import cn.shopledger.ui.WheelHaptics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28])
class WheelPreferencesTest {
    @Test fun strengthPersistsAcrossInstancesAndRejectsInvalidLevels() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("wheel_preferences",Context.MODE_PRIVATE).edit().clear().commit()
        val preferences=WheelPreferences(context)
        assertEquals(1,preferences.strength.value)
        for (strength in listOf(0,3,2,1)) {
            preferences.setStrength(strength)
            assertEquals(strength,preferences.strength.value)
            assertEquals(strength,WheelPreferences(context).strength.value)
        }
        assertThrows(IllegalArgumentException::class.java){preferences.setStrength(-1)}
        assertThrows(IllegalArgumentException::class.java){preferences.setStrength(4)}
        assertEquals(1,preferences.strength.value)
    }
    @Test fun motorFeedbackRespectsOffSystemSwitchHardwareAndStrength() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity=controller.get()
            val view=activity.window.decorView
            assertTrue(view.isAttachedToWindow)
            val vibrator=activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val motor=shadowOf(vibrator)
            motor.setHasVibrator(true)
            motor.setHasAmplitudeControl(true)
            Settings.System.putInt(activity.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1)
            val haptics=WheelHaptics(view)
            haptics.tick(0)
            assertFalse(motor.isVibrating)
            haptics.tick(1)
            assertTrue(motor.isVibrating)
            assertEquals(8L,motor.milliseconds)
            // Rapid duplicate crossings do not produce an uninterrupted buzz.
            haptics.tick(3)
            assertEquals(8L,motor.milliseconds)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            haptics.tick(3)
            assertEquals(18L,motor.milliseconds)
            vibrator.cancel()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Settings.System.putInt(activity.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,0)
            haptics.tick(3)
            assertFalse(motor.isVibrating)
            Settings.System.putInt(activity.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1)
            motor.setHasVibrator(false)
            haptics.tick(2)
            assertFalse(motor.isVibrating)
        } finally { controller.pause().stop().destroy() }
    }
}
