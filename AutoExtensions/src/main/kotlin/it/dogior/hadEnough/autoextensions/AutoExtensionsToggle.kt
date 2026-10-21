package it.dogior.hadEnough.autoextensions

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat

private const val TAG = "AutoExtensionsToggle"

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

fun mountToggle(host: FrameLayout, initialChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    host.removeAllViews()
    val accent = themeAccentColor(host.context)

    val mounted = runCatching { mountComposeSwitch(host, initialChecked, accent, onCheckedChange) }
        .onFailure { Log.w(TAG, "Compose Switch not mounted, falling back to the native one", it) }
        .getOrDefault(false)

    if (!mounted) {
        val switchView = SwitchCompat(host.context).apply {
            showText = false
            isChecked = initialChecked
            setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
        }
        host.addView(
            switchView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
    }
}

private fun mountComposeSwitch(
    host: FrameLayout,
    initialChecked: Boolean,
    accentColorInt: Int,
    onCheckedChange: (Boolean) -> Unit,
): Boolean {
    val composeView = androidx.compose.ui.platform.ComposeView(host.context)
    val state = androidx.compose.runtime.mutableStateOf(initialChecked)
    val accent = androidx.compose.ui.graphics.Color(accentColorInt)
    val white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val darkTrack = androidx.compose.ui.graphics.Color(0xFF35343A)
    val lightGreyBorderAndThumb = androidx.compose.ui.graphics.Color(0xFF928F98)
    composeView.setContent {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.material3.Switch(
                checked = state.value,
                onCheckedChange = { checked ->
                    state.value = checked
                    onCheckedChange(checked)
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = white,
                    checkedTrackColor = accent,
                    checkedBorderColor = accent,
                    uncheckedThumbColor = lightGreyBorderAndThumb,
                    uncheckedTrackColor = darkTrack,
                    uncheckedBorderColor = lightGreyBorderAndThumb,
                ),
            )
        }
    }
    host.addView(
        composeView,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    )
    return true
}
