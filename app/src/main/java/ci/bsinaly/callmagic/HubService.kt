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
        const val ACTION_SOS_SILENCE = "bscm.SOS_SILENCE"
        const val KEY_REPLY = "reply"
        const val EXTRA_CONV = "conv"
        private const val CH_SERVICE = "bscm_service"
        private const val CH_MSG = "bscm_msg"
        private const val CH_CALL = "bscm_call"
        private const val CH_SOS = "bscm_sos"
        private const val N_SOS = 4
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
        Hub.init(this)
        goForeground()
        Hub.events = this
        Hub.startNetworking()
        Interphone.init(this)
        VoiceRecorder.onMax = null
        locks()
        overlay = Overlay(this)
        applyBubble()
        startLocation()
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
                val conv = intent.getStringExtra(EXTRA_CONV) ?: ""
                if (!text.isNullOrBlank()) Hub.sendText(text, conv)
                cancelMessageNotification(conv)
            }
            ACTION_REJECT -> Hub.reject()
            ACTION_SOS_SILENCE -> {
                SosAlarm.stop()
                for (a in Sos.alerts) Sos.ack(a.id)
                try { nm.cancel(N_SOS) } catch (_: Exception) {}
            }
            else -> { goForeground(); Hub.startBtServer(); startLocation() }
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
        stopLocation()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ premier plan

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Service BS CALLMagic", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages reçus", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SOS, "Alertes SOS", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })
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

    private fun servicePi(code: Int, action: String, mutable: Boolean = false, conv: String? = null) =
        PendingIntent.getService(this, code, Intent(this, HubService::class.java).setAction(action).apply {
            if (conv != null) putExtra(EXTRA_CONV, conv)
        }, piFlags(mutable))

    private fun statusText(): String {
        val ps = Hub.readyPeers()
        val net = if (Relay.online) "Wi-Fi + Bluetooth + Internet" else "Wi-Fi + Bluetooth"
        return if (ps.isEmpty()) "En écoute ($net) — code ${Contacts.pretty(Hub.myCode())}"
        else "${ps.size} connecté(s) : " + ps.joinToString(", ") { "${it.name} ${Proto.icon(it.transport)}" }
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
            val withLoc = if (Loc.has(this)) types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else types
            // Du plus complet au plus simple : certains Android refusent un type selon les autorisations
            for (t in listOf(withLoc, types, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)) {
                try { startForeground(N_SERVICE, n, t); break } catch (_: Exception) {}
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
        if (Hub.visibleConv != m.conv) notifyMessage(m)
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
        val conv = m.conv
        if (Lock.isLocked(this)) {
            // Application verrouillée : aucune information sur l'écran de verrouillage ni dans la notification
            val n = builder(CH_MSG).setSmallIcon(R.drawable.ic_stat).setContentTitle("BS CALLMagic")
                .setContentText("🔒 Nouveau message").setAutoCancel(true)
                .setContentIntent(activityPi(3 + conv.hashCode(), Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_TARGET, conv)))
            try { nm.notify(conv, N_MSG, n.build()) } catch (_: Exception) {}
            return
        }
        val b = builder(CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle((if (conv.isEmpty()) "Salon · " else "🔒 ") + m.origin.ifBlank { "BS CALLMagic" })
            .setContentText(m.summary())
            .setStyle(Notification.BigTextStyle().bigText(m.summary()))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(activityPi(3 + conv.hashCode(), Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_TARGET, conv)))
        val n = Hub.unreadBy[conv] ?: 0
        if (n > 1) b.setSubText("$n nouveaux messages")
        if (Build.VERSION.SDK_INT < 26) legacyAlert(b, Notification.PRIORITY_HIGH, !Prefs.interphone(this))
        if (Build.VERSION.SDK_INT >= 24) {
            val ri = RemoteInput.Builder(KEY_REPLY).setLabel(if (conv.isEmpty()) "Répondre au salon…" else "Répondre à ${m.origin}…").build()
            b.addAction(Notification.Action.Builder(0, "Répondre", servicePi(4 + conv.hashCode(), ACTION_REPLY, mutable = true, conv = conv))
                .addRemoteInput(ri).build())
        }
        try { nm.notify(conv, N_MSG, b.build()) } catch (_: Exception) {}
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

    fun cancelMessageNotification(conv: String) = try { nm.cancel(conv, N_MSG) } catch (_: Exception) {}

    // ------------------------------------------------------------------ position (messages posés)

    private var locListener: android.location.LocationListener? = null

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locListener != null || !Loc.has(this)) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val l = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                Loc.last = loc
                try { Geo.check(this@HubService, loc) } catch (_: Exception) {}
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        locListener = l
        for ((prov, every) in listOf(android.location.LocationManager.NETWORK_PROVIDER to 60_000L,
                                     android.location.LocationManager.GPS_PROVIDER to 90_000L,
                                     android.location.LocationManager.PASSIVE_PROVIDER to 30_000L)) {
            try { lm.requestLocationUpdates(prov, every, 20f, l, android.os.Looper.getMainLooper()) } catch (_: Exception) {}
        }
        Loc.lastKnown(this)?.let { Loc.last = it; Geo.check(this, it) }
    }

    private fun stopLocation() {
        val l = locListener ?: return
        try { (getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(l) } catch (_: Exception) {}
        locListener = null
    }

    // ------------------------------------------------------------------ SOS et notifications simples

    /**
     * Alerte reçue. [loud] : sirène (40 s maximum, arrêtable depuis la notification, l'écran ou la bulle) ;
     * sinon simple notification. Une alerte arrêtée ne sonne plus jamais.
     */
    fun onSos(a: Sos.Alert, loud: Boolean) {
        val open = activityPi(40 + (a.id and 0xffff).toInt(), Intent(this, SosActivity::class.java))
        val b = builder(CH_SOS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🆘 ALERTE — ${a.name}" + if (!a.known) " (inconnu)" else "")
            .setContentText("${a.type}${if (a.note.isNotBlank()) " — " + a.note else ""}")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(0, "🔕 Arrêter la sirène", servicePi(41, ACTION_SOS_SILENCE)).build())
        if (loud) b.setFullScreenIntent(open, true)
        if (Build.VERSION.SDK_INT < 26) legacyAlert(b, Notification.PRIORITY_MAX, !loud)
        try { nm.notify(N_SOS, b.build()) } catch (_: Exception) {}
        if (loud) {
            SosAlarm.start(this, a.id)
            try { startActivity(Intent(this, SosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        overlay?.refresh()
    }

    fun onSosCancelled(ref: Long) {
        try { nm.cancel(N_SOS) } catch (_: Exception) {}
        SosAlarm.stop()
        Hub.changed()
    }

    private var simpleId = 100
    fun notifySimple(title: String, text: String) {
        val b = builder(CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(activityPi(90, Intent(this, MainActivity::class.java)))
        try { nm.notify(simpleId++, b.build()) } catch (_: Exception) {}
        if (simpleId > 140) simpleId = 100
    }
}
