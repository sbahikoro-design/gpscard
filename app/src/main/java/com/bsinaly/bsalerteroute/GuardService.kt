package com.bsinaly.bsalerteroute

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Service de surveillance : suit le GPS en continu et déclenche bip + voix + vibration
 * quand le véhicule entre dans le rayon d'un obstacle, dans le bon sens de circulation.
 */
class GuardService : Service(), LocationListener {

    companion object {
        const val ACT_START = "start"
        const val ACT_STOP = "stop"
        const val ACT_MARK = "mark"
        private const val CH_RUN = "bsar_run"
        private const val CH_ALERT = "bsar_alert"
        private const val NID = 41
        private const val NID_ALERT = 42

        fun start(ctx: Context) {
            val i = Intent(ctx, GuardService::class.java).setAction(ACT_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACT_STOP)) }
    }

    private lateinit var lm: LocationManager
    private lateinit var alerter: Alerter
    private lateinit var prefs: Prefs
    private var wake: PowerManager.WakeLock? = null
    /** id → étape d'alerte déjà jouée (1 = annonce, 2 = tout proche). */
    private val stage = HashMap<Long, Int>()
    private val lastDist = HashMap<Long, Float>()
    private var lastNotifAt = 0L
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        alerter = Alerter(this)
        prefs = Prefs(this)
        channels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { shutdown(); return START_NOT_STICKY }
            ACT_MARK -> { if (!started) begin(); markHere() }
            else -> begin()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun begin() {
        if (started) return
        val n = buildNotif("Surveillance active", "Recherche du signal GPS…")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NID, n)
        } catch (e: Exception) { stopSelf(); return }
        started = true
        Live.running = true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) { }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) { }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bsar:gps").also { it.acquire() }
    }

    private fun shutdown() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        Live.running = false
        Live.nearestId = -1; Live.nearestDist = -1f
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) { try { lm.removeUpdates(this) } catch (_: Exception) {}; try { wake?.release() } catch (_: Exception) {} }
        Live.running = false
        alerter.release()
        super.onDestroy()
    }

    /** Pointage depuis la notification (sans ouvrir l'appli). */
    private fun markHere() {
        val loc = Live.last
        if (loc == null || System.currentTimeMillis() - Live.gpsFixAt > 15000) {
            alerter.say("Pas de signal GPS, obstacle non enregistré"); return
        }
        val (o, merged) = Store.add(this, loc, prefs.lastType)
        stage[o.id] = 9 // on est dessus : pas d'alerte immédiate
        alerter.marked()
        alerter.say(if (merged) "${Types.of(o.type).voice} confirmé" else "${Types.of(o.type).voice} enregistré", 300)
    }

    // ---------------- Cœur intelligent ----------------

    override fun onLocationChanged(loc: Location) {
        // On ignore une position réseau si le GPS précis est récent
        if (loc.provider == LocationManager.NETWORK_PROVIDER && Live.last?.provider == LocationManager.GPS_PROVIDER
            && System.currentTimeMillis() - Live.gpsFixAt < 6000) return
        if (loc.accuracy > 80f && loc.provider == LocationManager.NETWORK_PROVIDER) { Live.last = loc; return }
        Live.last = loc
        Live.gpsFixAt = System.currentTimeMillis()

        val speed = if (loc.hasSpeed()) loc.speed else 0f           // m/s
        val moving = speed > 2.5f && loc.hasBearing()
        val obs = Store.all(this)

        var bestO: Obstacle? = null; var bestD = Float.MAX_VALUE
        var nearestAhead: Obstacle? = null; var nearestAheadD = Float.MAX_VALUE

        for (o in obs) {
            val d = loc.distanceTo(o.loc())
            val prev = lastDist[o.id]
            lastDist[o.id] = d
            // Rayon dynamique : rayon de base, ou distance parcourue en N secondes si on roule vite
            val trig = max(o.effRadius().toFloat(), speed * prefs.anticipation) + loc.accuracy.coerceAtMost(30f) / 2f
            if (Live.justMarked.remove(o.id)) stage[o.id] = 9 // on vient de le pointer : pas d'alerte
            val st = stage[o.id] ?: 0

            // Ré-armement : une fois l'obstacle bien dépassé, il pourra de nouveau alerter au prochain passage
            if (st > 0 && d > trig * 1.5f + 40f) { stage.remove(o.id); continue }
            if (o.muted) continue

            val ahead = isAhead(loc, o, d, moving)
            val approaching = prev == null || d <= prev + 2f
            if (ahead && approaching && d < nearestAheadD) { nearestAhead = o; nearestAheadD = d }

            if (st == 0 && d <= trig && ahead && approaching && d < bestD) { bestO = o; bestD = d }
            if (st == 1 && d <= 35f && ahead) { stage[o.id] = 2; alerter.close() }
        }

        bestO?.let { o ->
            stage[o.id] = 1
            alerter.warn(o, bestD)
            Live.alertText = "${Types.of(o.type).label} à ${fmtDist(bestD)}"
            Live.alertAt = System.currentTimeMillis()
            Store.update(this, o.id) { it.alerts++ }
            alertNotif(o, bestD)
        }

        Live.nearestId = nearestAhead?.id ?: -1
        Live.nearestDist = if (nearestAhead != null) nearestAheadD else -1f

        val now = System.currentTimeMillis()
        if (now - lastNotifAt > 4000) {
            lastNotifAt = now
            val kmh = (speed * 3.6f).toInt()
            val txt = nearestAhead?.let { "Prochain : ${Types.of(it.type).label} à ${fmtDist(nearestAheadD)}" }
                ?: "Aucun obstacle devant · ${obs.size} enregistrés"
            nm().notify(NID, buildNotif("Surveillance active · $kmh km/h", txt))
        }
    }

    /**
     * L'obstacle est-il devant le véhicule ?
     * - à l'arrêt ou sans cap fiable : on considère que oui (prudence) ;
     * - en mouvement (option « sens de circulation ») : l'obstacle doit être dans un cône
     *   de ±65° autour du cap ; un obstacle déjà dépassé ou sur une route parallèle ne sonne pas.
     */
    private fun isAhead(loc: Location, o: Obstacle, d: Float, moving: Boolean): Boolean {
        if (!moving || d < 30f) return true
        if (!prefs.smartDir) return true
        val diff = angleDiff(loc.bearing, loc.bearingTo(o.loc()))
        return diff <= 65f
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var x = abs(((a - b) % 360f + 360f) % 360f)
        if (x > 180f) x = 360f - x
        return x
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) alerter.say("Attention, le GPS est désactivé")
    }

    // ---------------- Notifications ----------------

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val m = nm()
        m.createNotificationChannel(NotificationChannel(CH_RUN, "Surveillance du trajet", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null)
        })
        m.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertes obstacles", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // le son est déjà joué par l'appli (bips + voix)
            enableVibration(false)
        })
    }

    private fun pi(action: String?, code: Int): PendingIntent {
        val fl = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == null)
            PendingIntent.getActivity(this, code, Intent(this, MainActivity::class.java), fl)
        else PendingIntent.getService(this, code, Intent(this, GuardService::class.java).setAction(action), fl)
    }

    private fun buildNotif(title: String, text: String): Notification =
        Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFFFFD000.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi(null, 1))
            .addAction(Notification.Action.Builder(null, "⚠ Obstacle ici (${Types.of(prefs.lastType).label})", pi(ACT_MARK, 2)).build())
            .addAction(Notification.Action.Builder(null, "Arrêter", pi(ACT_STOP, 3)).build())
            .build()

    private fun alertNotif(o: Obstacle, d: Float) {
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⚠ ${Types.of(o.type).label}")
            .setContentText("À ${fmtDist(d)} devant vous — ralentissez")
            .setColor(Types.of(o.type).color)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setTimeoutAfter(15000)
            .setAutoCancel(true)
            .setContentIntent(pi(null, 4))
            .build()
        try { nm().notify(NID_ALERT, n) } catch (_: Exception) {}
    }
}
