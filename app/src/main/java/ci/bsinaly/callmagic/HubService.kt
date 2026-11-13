package ci.bsinaly.callmagic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Vibrator
import android.provider.Settings

/**
 * Service permanent : garde les connexions ouvertes, écoute Wi-Fi + Bluetooth, réagit aux messages
 * même quand l'application est fermée (notifications, interphone, bulle flottante, appels entrants).
 */
class HubService : Service(), Hub.Events {

    companion object {
        const val ACTION_STOP = "bscm.STOP"
        const val ACTION_REPLY = "bscm.REPLY"
        const val ACTION_REJECT = "bscm.REJECT"
        const val ACTION_REFRESH = "bscm.REFRESH"
        const val KEY_REPLY = "reply"
        private const val CH_SERVICE = "bscm_service"
        private const val CH_MSG = "bscm_msg"
        private const val CH_CALL = "bscm_call"
        private const val N_SERVICE = 1
        private const val N_MSG = 2
        private const val N_CALL = 3

        @Volatile var instance: HubService? = null
            private set

        fun start(c: Context, action: String? = null) {
            val i = Intent(c, HubService::class.java)
            if (action != null) i.action = action
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) {}
        }

        fun stop(c: Context) = start(c, ACTION_STOP)
    }

    private var overlay: Overlay? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var ringtone: Ringtone? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels()
        goForeground()
        Hub.init(this)
        Hub.events = this
        Hub.startNetworking()
        Interphone.init(this)
        locks()
        overlay = Overlay(this)
        applyBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Hub.stopAll()
                overlay?.hide()
                removeForeground()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (!text.isNullOrBlank()) Hub.sendText(text)
                nm.cancel(N_MSG)
            }
            ACTION_REJECT -> Hub.reject()
            else -> { goForeground(); Hub.startBtServer() }
        }
        applyBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        if (Hub.events === this) Hub.events = null
        stopRinging()
        overlay?.hide()
        Interphone.shutdown()
        try { wake?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        try { mcLock?.release() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ premier plan

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Service BS CALLMagic", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages reçus", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Appels entrants", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // la sonnerie est jouée par le service
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    @Suppress("DEPRECATION")
    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    @Suppress("DEPRECATION")
    private fun builder(ch: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)

    private fun piFlags(mutable: Boolean = false): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or when {
            mutable && Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            !mutable && Build.VERSION.SDK_INT >= 23 -> PendingIntent.FLAG_IMMUTABLE
            else -> 0
        }

    private fun activityPi(code: Int, i: Intent) =
        PendingIntent.getActivity(this, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags())

    private fun servicePi(code: Int, action: String, mutable: Boolean = false) =
        PendingIntent.getService(this, code, Intent(this, HubService::class.java).setAction(action), piFlags(mutable))

    private fun statusText(): String {
        val ps = Hub.readyPeers()
        return if (ps.isEmpty()) "En écoute (Wi-Fi + Bluetooth) — aucun correspondant connecté"
        else "${ps.size} connecté(s) : " + ps.joinToString(", ") { it.name }
    }

    private fun serviceNotification(): Notification =
        builder(CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BS CALLMagic est actif")
            .setContentText(statusText())
            .setOngoing(true)
            .setContentIntent(activityPi(1, Intent(this, ChatActivity::class.java)))
            .addAction(Notification.Action.Builder(0, "Quitter", servicePi(2, ACTION_STOP)).build())
            .build()

    private fun goForeground() {
        val n = serviceNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try { startForeground(N_SERVICE, n, types) }
            catch (e: Exception) {
                try { startForeground(N_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) } catch (_: Exception) {}
            }
        } else startForeground(N_SERVICE, n)
    }

    @Suppress("DEPRECATION")
    private fun locks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bscallmagic:hub").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bscallmagic:wifi").apply { setReferenceCounted(false); acquire() }
            mcLock = wm.createMulticastLock("bscallmagic:mc").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    fun applyBubble() {
        val want = Prefs.bubble(this) && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
        if (want) overlay?.show() else overlay?.hide()
    }

    fun refreshOverlay() = overlay?.refresh()

    // ------------------------------------------------------------------ événements du Hub

    override fun onIncoming(m: Msg) {
        Interphone.announce(this, m)
        overlay?.onMessage(m)
        if (!Hub.chatVisible) notifyMessage(m)
    }

    override fun onPeersChanged() {
        try { nm.notify(N_SERVICE, serviceNotification()) } catch (_: Exception) {}
        overlay?.refresh()
    }

    override fun onCallChanged() {
        overlay?.refresh()
        if (Hub.call == Hub.Call.RINGING_IN) {
            startRinging()
            notifyCall()
            try {
                startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        } else {
            stopRinging()
            nm.cancel(N_CALL)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyAlert(b: Notification.Builder, prio: Int, sound: Boolean) {
        b.setPriority(prio)
        if (sound) b.setDefaults(Notification.DEFAULT_ALL)
    }

    @Suppress("DEPRECATION")
    private fun notifyMessage(m: Msg) {
        val b = builder(CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(m.origin.ifBlank { "BS CALLMagic" })
            .setContentText(m.summary())
            .setStyle(Notification.BigTextStyle().bigText(m.summary()))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(activityPi(3, Intent(this, ChatActivity::class.java)))
        if (Hub.unread > 1) b.setSubText("${Hub.unread} nouveaux messages")
        if (Build.VERSION.SDK_INT < 26) legacyAlert(b, Notification.PRIORITY_HIGH, !Prefs.interphone(this))
        if (Build.VERSION.SDK_INT >= 24) {
            val ri = RemoteInput.Builder(KEY_REPLY).setLabel("Répondre à tous…").build()
            b.addAction(Notification.Action.Builder(0, "Répondre", servicePi(4, ACTION_REPLY, mutable = true))
                .addRemoteInput(ri).build())
        }
        try { nm.notify(N_MSG, b.build()) } catch (_: Exception) {}
    }

    private fun notifyCall() {
        val p = Hub.callPeer ?: return
        val open = activityPi(5, Intent(this, CallActivity::class.java))
        val answer = activityPi(6, Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_ACCEPT, true))
        val b = builder(CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (Hub.callVideo) "🎥 Appel vidéo entrant" else "📞 Appel entrant")
            .setContentText("${p.name} vous appelle (${p.transport})")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(0, "Refuser", servicePi(7, ACTION_REJECT)).build())
            .addAction(Notification.Action.Builder(0, "Décrocher", answer).build())
        if (Build.VERSION.SDK_INT < 26) legacyAlert(b, Notification.PRIORITY_MAX, false)
        try { nm.notify(N_CALL, b.build()) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun startRinging() {
        Interphone.stopAll()
        if (ringtone?.isPlaying == true) return
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= 21) audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        } catch (_: Exception) {}
        try { (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(longArrayOf(0, 900, 900), 0) } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() } catch (_: Exception) {}
    }

    fun cancelMessageNotification() = try { nm.cancel(N_MSG) } catch (_: Exception) {}
}
