package it.dogior.hadEnough.watchparty

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.widget.ImageButton
import android.widget.ImageView

/**
 * Crea il FAB del player, scegliendo l'implementazione giusta per l'host:
 *  - CloudStream: com.google.android.material.floatingactionbutton.
 *    FloatingActionButton vera (che CloudStream ha già nel suo classpath,
 *    essendo un'app Material) — stesso aspetto/animazioni di sempre.
 *  - Altri host (es. Nuvio Enhanced, dove Material non è disponibile a
 *    runtime — vedi NUVIO_COMPATIBILITY_NOTES.md): un ImageButton
 *    circolare fatto a mano (GradientDrawable + RippleDrawable), senza
 *    dipendenze esterne.
 *
 * FloatingActionButton eredita comunque da ImageButton (tramite
 * AppCompatImageButton), quindi entrambi i rami restituiscono lo stesso
 * tipo FabButton: nel resto del codice (WatchPartyOverlay.kt) non cambia
 * nulla, si continua a chiamare setImageDrawable/setOnClickListener/alpha
 * come sempre.
 *
 * Il ramo Material è isolato in createMaterialFab(), MAI chiamato quando
 * WatchPartyPlayback.isCloudStreamHost è false: grazie alla risoluzione
 * pigra delle classi di Android (stessa protezione già usata per
 * CloudStreamPlaybackBridge/IPlayer), quel riferimento a
 * FloatingActionButton non viene mai risolto su un host senza Material,
 * quindi non causa NoClassDefFoundError anche se la libreria non è
 * disponibile lì.
 */
typealias FabButton = ImageButton

fun createFabButton(activity: Activity, sizeDp: Int = 56, backgroundColor: Int = 0x99000000.toInt()): FabButton {
    if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { createMaterialFab(activity, backgroundColor) }.getOrNull()?.let { return it }
        // Se anche su CloudStream qualcosa va storto (versione insolita
        // dell'app, Material mancante per qualche motivo), non blocchiamo
        // l'utente: cadiamo sul FAB fatto a mano qui sotto.
    }
    return createPlainFab(activity, sizeDp, backgroundColor)
}

private fun createMaterialFab(
    activity: Activity,
    backgroundColor: Int,
): com.google.android.material.floatingactionbutton.FloatingActionButton =
    com.google.android.material.floatingactionbutton.FloatingActionButton(activity).apply {
        backgroundTintList = ColorStateList.valueOf(backgroundColor)
        imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
    }

private fun createPlainFab(activity: Activity, sizeDp: Int, backgroundColor: Int): FabButton {
    val density = activity.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()

    val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(backgroundColor)
    }
    val foreground: Drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), circle, circle)
    } else {
        circle
    }

    return ImageButton(activity).apply {
        setBackground(foreground)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = sizePx
        minimumHeight = sizePx
        val pad = (sizePx * 0.28f).toInt()
        setPadding(pad, pad, pad, pad)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = 8f * density
        }
        // niente tint di sfondo di sistema: il cerchio colorato è già lo sfondo
        setColorFilter(android.graphics.Color.WHITE)
    }
}
