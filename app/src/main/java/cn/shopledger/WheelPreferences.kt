package cn.shopledger

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device interaction preferences never rewrite business records or backup data. */
class WheelPreferences(context: Context) {
    private val store = context.getSharedPreferences("wheel_preferences", Context.MODE_PRIVATE)
    private val value = MutableStateFlow(store.getInt("strength", 1).takeIf { it in 0..3 } ?: 1)
    val strength = value.asStateFlow()
    fun setStrength(level: Int) {
        require(level in 0..3)
        store.edit().putInt("strength", level).apply()
        value.value = level
    }
}
