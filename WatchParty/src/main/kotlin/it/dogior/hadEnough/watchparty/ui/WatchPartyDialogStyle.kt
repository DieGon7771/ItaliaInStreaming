package it.dogior.hadEnough.watchparty

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.app.AlertDialog

/**
 * Crea un AlertDialog.Builder, scegliendo l'implementazione giusta per l'host:
 *  - CloudStream: MaterialAlertDialogBuilder vera.
 *  - Altri host (es. Nuvio, dove Material non è disponibile a runtime):
 *    AlertDialog.Builder di AppCompat, vestito con styleAsWatchPartyPanel.
 *
 * Il tipo qui è androidx.appcompat.app.AlertDialog, non android.app.AlertDialog
 * — MaterialAlertDialogBuilder estende quella gerarchia, non quella di
 * sistema. AppCompat è comunque presente su entrambi gli host.
 *
 * Stessa protezione di FabButton.kt: il riferimento a
 * MaterialAlertDialogBuilder vive in una funzione separata, chiamata solo
 * quando isCloudStreamHost è true (risoluzione pigra delle classi).
 */
fun newAlertDialogBuilder(context: Context): AlertDialog.Builder {
    if (WatchPartyPlayback.isCloudStreamHost) {
        runCatching { return newMaterialAlertDialogBuilder(context) }
    }
    return AlertDialog.Builder(context)
}

private fun newMaterialAlertDialogBuilder(context: Context): AlertDialog.Builder =
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)

/**
 * Applica lo sfondo del pannello alla Window di un Dialog, solo quando serve
 * (non su una vera MaterialAlertDialogBuilder, che ha già il suo aspetto).
 *
 * Il [background] va risolto dal chiamante col proprio getDrawable(name)
 * locale, non con un riferimento diretto a R.drawable: gli ID R compilati
 * nel plugin non sono affidabili una volta che le risorse vengono agganciate
 * dinamicamente nell'host.
 */
fun styleAsWatchPartyPanel(dialog: Dialog?, background: Drawable?) {
    if (WatchPartyPlayback.isCloudStreamHost) return // già vestito da Material
    val window = dialog?.window ?: return
    if (background == null) return
    runCatching {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.findViewById<android.view.View>(android.R.id.content)
            ?.background = background
    }
}
