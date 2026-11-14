package it.dogior.hadEnough

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object NuvioCrypto {
    private val random = SecureRandom()

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    fun unhex(s: String): ByteArray {
        val n = s.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
        return out
    }

    fun hash(algo: String, hexData: String): String? = try {
        hex(MessageDigest.getInstance(algo).digest(unhex(hexData)))
    } catch (_: Exception) { null }

    fun hmac(macName: String, hexKey: String, hexData: String): String? = try {
        val key = unhex(hexKey)
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, macName))
        hex(mac.doFinal(unhex(hexData)))
    } catch (_: Exception) { null }

    fun random(n: Int): String {
        val b = ByteArray(n.coerceIn(0, 4096))
        random.nextBytes(b)
        return hex(b)
    }

    fun cipher(algo: String, op: String, mode: String, pad: String, hexKey: String, hexIv: String, hexData: String): String? {
        return try {
            val block = if (algo == "AES") 16 else 8
            var key = unhex(hexKey)
            if (algo == "DESede" && key.size == 16) key += key.copyOfRange(0, 8)
            val m = mode.uppercase()
            val transformation = "$algo/$m/NoPadding"
            val spec = SecretKeySpec(key, algo)
            val encrypt = op == "encrypt"
            val c = Cipher.getInstance(transformation)
            val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
            if (m == "ECB") c.init(opMode, spec)
            else {
                var iv = unhex(hexIv)
                if (iv.size != block) iv = iv.copyOf(block)
                c.init(opMode, spec, IvParameterSpec(iv))
            }
            var data = unhex(hexData)
            if (encrypt) {
                data = when (pad) {
                    "Pkcs7" -> {
                        val n = block - (data.size % block)
                        data + ByteArray(n) { n.toByte() }
                    }
                    "ZeroPadding" -> {
                        val n = (block - (data.size % block)) % block
                        data + ByteArray(n)
                    }
                    else -> data
                }
                hex(c.doFinal(data))
            } else {
                var out = c.doFinal(data)
                out = when (pad) {
                    "Pkcs7" -> {
                        if (out.isEmpty()) return null
                        val n = out.last().toInt() and 0xff
                        if (n < 1 || n > block || n > out.size) return null
                        for (i in out.size - n until out.size) if ((out[i].toInt() and 0xff) != n) return null
                        out.copyOf(out.size - n)
                    }
                    "ZeroPadding" -> {
                        var e = out.size
                        while (e > 0 && out[e - 1].toInt() == 0) e--
                        out.copyOf(e)
                    }
                    else -> out
                }
                hex(out)
            }
        } catch (_: Exception) { null }
    }
}
