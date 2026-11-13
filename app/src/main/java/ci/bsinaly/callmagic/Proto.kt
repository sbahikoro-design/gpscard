package ci.bsinaly.callmagic

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Types de trames : [type: 1 octet][longueur: 4 octets][données]. Protocole v3. */
object Proto {
    const val VERSION = 3

    const val HELLO = 1        // str id, str nom, int version
    const val TEXT = 2         // long clé, str idOrigine, str nomOrigine, str dest, str texte
    const val FILE_START = 3   // long clé, str idOrigine, str nomOrigine, str dest, long taille, str nom
    const val FILE_CHUNK = 4   // long clé + données brutes
    const val FILE_END = 5     // long clé
    const val CALL_REQ = 6     // octet : 1 = vidéo
    const val CALL_ACCEPT = 7
    const val CALL_REJECT = 8
    const val CALL_END = 9
    const val AUDIO = 10       // PCM 16 bits mono
    const val BYE = 11
    const val VOICE = 12       // long clé, str idOrigine, str nomOrigine, str dest, int durée ms, int n, octets (m4a)
    const val VIDEO = 13       // 2 octets rotation + JPEG
    const val PING = 14        // maintien de la liaison

    const val WIFI = "Wi-Fi"
    const val BT = "Bluetooth"
    const val NET_IP = "Internet (IP)"
    const val NET_CODE = "Internet (code)"

    val EMPTY = ByteArray(0)

    fun icon(transport: String) = when (transport) {
        WIFI -> "📶"
        BT -> "🔵"
        NET_CODE -> "🔒🌐"
        else -> "🌐"
    }

    /** Adresse du réseau local (Wi-Fi, point d'accès) ou adresse joignable par Internet ? */
    fun isLocal(a: InetAddress?): Boolean {
        if (a == null) return true
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress) return true
        val b = a.address
        if (a is Inet4Address) return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 // 100.64/10
        if (a is Inet6Address) return (b[0].toInt() and 0xfe) == 0xfc // fc00::/7
        return false
    }
}

fun pack(f: DataOutputStream.() -> Unit): ByteArray {
    val b = ByteArrayOutputStream()
    DataOutputStream(b).apply { f(); flush() }
    return b.toByteArray()
}

fun DataOutputStream.putStr(s: String) {
    val b = s.toByteArray(Charsets.UTF_8)
    writeInt(b.size); write(b)
}

fun DataInputStream.getStr(): String {
    val n = readInt()
    if (n < 0 || n > 2_000_000) throw IOException("chaîne invalide")
    val b = ByteArray(n)
    readFully(b)
    return String(b, Charsets.UTF_8)
}

fun DataOutputStream.putBytes(b: ByteArray) { writeInt(b.size); write(b) }

fun DataInputStream.getBytes(max: Int = 8_000_000): ByteArray {
    val n = readInt()
    if (n < 0 || n > max) throw IOException("bloc invalide")
    val b = ByteArray(n)
    readFully(b)
    return b
}
