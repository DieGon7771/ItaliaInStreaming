package it.dogior.hadEnough.watchparty

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat

private const val TAG = "WatchParty"

/**
 * Colore "accento" del tema reale dell'app, letto dal Context invece di
 * essere hardcoded: senza questo uno Switch costruito a mano non eredita
 * i colori del tema come faceva quello scritto nell'XML.
 *
 * Prova colorAccent di AppCompat, poi quello di sistema, poi un blu fisso
 * come ultima rete di sicurezza — mai un crash per questo.
 */
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

    return 0xFF2E7DFF.toInt() // stesso blu di riserva già usato altrove nel plugin
}

/**
 * Monta un interruttore dentro [host]: un vero Material3 Compose Switch se
 * siamo su CloudStream e Compose risolve, altrimenti un SwitchCompat
 * classico (NON android.widget.Switch grezzo: quello non si tinge come
 * l'originale, perché l'upgrade automatico a SwitchCompat lo fa AppCompat
 * solo quando la view è inflazionata da XML, non costruita a mano).
 * Entrambi i rami prendono il colore vero dal tema con themeAccentColor().
 *
 * Il ramo Compose, se qualcosa va storto, cade subito sul fallback — mai
 * un crash. [initialChecked]/[onCheckedChange] hanno la stessa semantica
 * di uno Switch normale: il chiamante non sa se sotto c'è Compose o no.
 */
fun mountToggle(host: FrameLayout, initialChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    host.removeAllViews()
    val accent = themeAccentColor(host.context)

    val mountedCompose = if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { mountComposeSwitch(host, initialChecked, accent, onCheckedChange) }
            .onFailure { Log.w(TAG, "Compose Switch non montato, uso quello nativo", it) }
            .getOrDefault(false)
    } else {
        false
    }

    if (!mountedCompose) {
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

/**
 * Isolata in una funzione a parte: referenzia le classi Compose SOLO qui,
 * chiamata solo quando isCloudStreamHost è vero — grazie alla risoluzione
 * pigra delle classi, su un host senza Compose questa funzione non viene
 * mai caricata, quindi non causa NoClassDefFoundError (stessa protezione
 * già usata per FabButton/AlertDialog/IPlayer).
 */
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
                    // acceso: tema; spento: valori estratti dal tema reale (#35343A / #928F98)
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
