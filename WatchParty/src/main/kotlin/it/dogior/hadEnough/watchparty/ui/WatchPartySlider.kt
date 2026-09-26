package it.dogior.hadEnough.watchparty

import android.util.Log
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.abs

private const val TAG = "WatchParty"

/**
 * Monta uno slider a scatti discreti dentro [host] (un FrameLayout vuoto
 * nell'XML): un vero Material3 Compose Slider su CloudStream, un SeekBar
 * nativo altrove (Nuvio, o se Compose non risolve per qualche motivo).
 * Stessa identica strategia di mountToggle() in WatchPartyComposeSwitch.kt.
 *
 * [steps] è la lista dei valori selezionabili in ordine (es. [50,100,150,
 * ...]); [onValueChange] riceve il VALORE (non l'indice) più vicino a dove
 * l'utente ha lasciato lo slider.
 */
fun mountSlider(host: FrameLayout, steps: List<Int>, initialValue: Int, onValueChange: (Int) -> Unit) {
    host.removeAllViews()
    require(steps.size >= 2) { "mountSlider richiede almeno 2 valori" }
    val accentColorInt = themeAccentColor(host.context)
    val closestIndex = steps.indices.minByOrNull { abs(steps[it] - initialValue) } ?: 0

    val mountedCompose = if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { mountComposeSlider(host, steps, closestIndex, accentColorInt, onValueChange) }
            .onFailure { Log.w(TAG, "Compose Slider non montato, uso quello nativo", it) }
            .getOrDefault(false)
    } else {
        false
    }

    if (!mountedCompose) {
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

/**
 * Isolata in una funzione a parte apposta, come mountComposeSwitch: mai
 * caricata/verificata su un host senza Compose, grazie alla risoluzione
 * pigra delle classi di Android.
 */
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
    // Colore fisso richiesto per: pista non selezionata + pallini degli scatti
    // sulla parte selezionata (invertito rispetto ai pallini sulla parte non
    // selezionata, che restano nel colore tema come la pista attiva).
    val fixedTone = androidx.compose.ui.graphics.Color(0xFF494458)
    composeView.setContent {
        androidx.compose.material3.MaterialTheme {
            androidx.compose.material3.Slider(
                value = state.value,
                onValueChange = { newValue ->
                    // Aggiornato in tempo reale (anche col dito ancora sopra),
                    // non solo al rilascio: stesso comportamento di Nuvio.
                    state.value = newValue
                    val idx = newValue.toInt().coerceIn(0, steps.size - 1)
                    onValueChange(steps[idx])
                },
                valueRange = 0f..(steps.size - 1).toFloat(),
                steps = steps.size - 2, // "steps" in M3 = tacche INTERMEDIE, escluse le due estremità
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = fixedTone,
                    activeTickColor = fixedTone,
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
