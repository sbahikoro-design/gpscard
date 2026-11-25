package com.bsinaly.bsnotifspeak

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Bouton « Notif Speak » dans le volet des réglages rapides : un appui = activer / désactiver. */
class SpeakTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val p = Prefs(this)
        p.enabled = !p.enabled
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = Prefs(this).enabled
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Notif Speak"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Lecture activée" else "En pause"
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        t.updateTile()
    }
}
