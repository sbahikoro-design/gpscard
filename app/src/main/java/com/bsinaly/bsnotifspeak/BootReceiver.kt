package com.bsinaly.bsnotifspeak

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.quicksettings.TileService

/** À l'allumage du téléphone : on s'assure que le service d'écoute est bien reconnecté. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            NotificationListenerService.requestRebind(ComponentName(ctx, SpeakService::class.java))
        } catch (_: Exception) {}
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, SpeakTile::class.java))
        } catch (_: Exception) {}
    }
}
