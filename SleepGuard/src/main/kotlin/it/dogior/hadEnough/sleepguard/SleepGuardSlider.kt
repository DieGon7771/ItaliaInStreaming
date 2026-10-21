package it.dogior.hadEnough.sleepguard

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.abs

private const val TAG = "SleepGuardSlider"

fun mountSlider(host: FrameLayout, steps: List<Int>, initialValue: Int, onValueChange: (Int) -> Unit) {
    host.removeAllViews()
    require(steps.size >= 2) { "mountSlider needs at least 2 values" }
    val accentColorInt = themeAccentColor(host.context)
    val closestIndex = steps.indices.minByOrNull { abs(steps[it] - initialValue) } ?: 0

    val mounted = runCatching { mountComposeSlider(host, steps, closestIndex, accentColorInt, onValueChange) }
        .onFailure { Log.w(TAG, "Compose Slider not mounted, falling back to the native one", it) }
        .getOrDefault(false)

    if (!mounted) {
        val seekBar = SeekBar(host.context).apply {
            max = steps.size - 1
            progress = closestIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onValueChange(steps[progress.coerceIn(0, steps.size - 1)])
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        host.addView(
            seekBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
    }
}

private fun mountComposeSlider(
    host: FrameLayout,
    steps: List<Int>,
    initialIndex: Int,
    accentColorInt: Int,
    onValueChange: (Int) -> Unit,
): Boolean {
    val composeView = androidx.compose.ui.platform.ComposeView(host.context)
    val state = androidx.compose.runtime.mutableStateOf(initialIndex.toFloat())
    val accent = androidx.compose.ui.graphics.Color(accentColorInt)
    val unselectedColor = androidx.compose.ui.graphics.Color(0xFF494458)
    fun commit(value: Float) {
        val idx = value.toInt().coerceIn(0, steps.size - 1)
        onValueChange(steps[idx])
    }
    composeView.setContent {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.material3.Slider(
                value = state.value,
                onValueChange = {
                    state.value = it
                    commit(it)
                },
                onValueChangeFinished = { commit(state.value) },
                valueRange = 0f..(steps.size - 1).toFloat(),
                steps = steps.size - 2,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = unselectedColor,
                    activeTickColor = unselectedColor,
                    inactiveTickColor = accent,
                ),
            )
        }
    }
    host.addView(
        composeView,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    )
    return true
}

fun themeAccentColor(context: Context): Int {
    val typedValue = TypedValue()
    val fromAppCompat = runCatching {
        val a = context.theme.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorAccent))
        val color = a.getColor(0, 0)
        a.recycle()
        color.takeIf { it != 0 }
    }.getOrNull()
    if (fromAppCompat != null) return fromAppCompat

    val fromPlatform = runCatching {
        if (context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)) typedValue.data else null
    }.getOrNull()
    if (fromPlatform != null) return fromPlatform

    return 0xFF2E7DFF.toInt()
}
