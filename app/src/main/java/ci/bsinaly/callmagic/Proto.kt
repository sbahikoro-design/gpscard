package ci.bsinaly.callmagic

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Types de trames : [type: 1 octet][longueur: 4 octets][données]. */
object Proto {
    const val HELLO = 1        // str id, str nom
    const val TEXT = 2         // long clé, str origine, str texte
    const val FILE_START = 3   // long clé, str origine, long taille, str nom
    const val FILE_CHUNK = 4   // long clé + données brutes
    const val FILE_END = 5     // long clé
    const val CALL_REQ = 6     // octet : 1 = vidéo
    const val CALL_ACCEPT = 7
    const val CALL_REJECT = 8
    const val CALL_END = 9
    const val AUDIO = 10       // PCM 16 bits mono
    const val BYE = 11
    const val VOICE = 12       // long clé, str origine, int durée ms, int n, octets (m4a)
    const val VIDEO = 13       // 2 octets rotation + JPEG

    const val WIFI = "Wi-Fi"
    const val BT = "Bluetooth"

    val EMPTY = ByteArray(0)
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
