package com.bsinaly.bsmagickeyboard

import java.util.concurrent.CopyOnWriteArrayList

/** Ce que le téléphone sait envoyer au PC, quel que soit le canal (Bluetooth ou Wi-Fi). */
interface Transport {
    fun isReady(): Boolean
    fun move(dx: Int, dy: Int)
    /** Masque des boutons : 1 = gauche, 2 = droit, 4 = milieu. */
    fun buttons(mask: Int)
    /** Molette : positif = vers le haut. */
    fun scroll(v: Int)
    fun text(s: String)
    fun key(name: String, mods: Int = 0)
    fun media(name: String)
}

/** Canal actif + notifications d'état pour l'interface. */
object Link {
    @Volatile var current: Transport? = null
    @Volatile var azerty = true

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.forEach { it() } }

    fun t(): Transport? = current?.takeIf { it.isReady() }

    private var mask = 0
    fun press(bit: Int, down: Boolean) {
        mask = if (down) mask or bit else mask and bit.inv()
        t()?.buttons(mask)
    }
    fun click(bit: Int) { press(bit, true); press(bit, false) }

    fun connected(): Boolean = t() != null

    fun status(): String = when {
        current === BtHid && BtHid.isReady() -> BtHid.state
        current === WifiLink && WifiLink.isReady() -> WifiLink.state
        else -> "Non connecté"
    }
}
