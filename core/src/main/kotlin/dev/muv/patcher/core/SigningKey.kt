package dev.muv.patcher.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 设备内签名身份：RSA 2048 + 自签证书（PKCS#8 与 DER 证书存放于私有目录）。 */
class SigningKey(
    val privateKey: PrivateKey,
    val certificate: ByteArray,
) {
    val fingerprint: String by lazy {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { "%02x".format(it) }
    }

    fun toKeyStore(password: CharArray): KeyStore {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.inputStream()) as X509Certificate
        return KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("muv", privateKey, password, arrayOf(cert))
        }
    }

    companion object {
        private const val KEY_FILE = "signing-key.der"
        private const val CERT_FILE = "signing-cert.der"

        fun loadOrCreate(directory: File): SigningKey {
            val keyFile = File(directory, KEY_FILE)
            val certFile = File(directory, CERT_FILE)
            if (keyFile.isFile && certFile.isFile) {
                return SigningKey(decodePrivateKey(keyFile.readBytes()), certFile.readBytes())
            }
            directory.mkdirs()
            val generated = generate()
            keyFile.writeBytes(generated.privateKey.encoded)
            certFile.writeBytes(generated.certificate)
            return generated
        }

        fun generate(): SigningKey {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val pair = generator.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 3600 * 1000)
            val notAfter = Date(now + 20L * 365 * 24 * 3600 * 1000)
            val name = Der.name("Muv Patcher")
            val algorithm = Der.algorithmSha256Rsa()

            val tbs = Der.sequence(
                Der.tlv(0xA0, Der.integer(BigInteger.valueOf(2))),
                Der.integer(BigInteger(72, SecureRandom()).abs().add(BigInteger.ONE)),
                algorithm,
                name,
                Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter)),
                name,
                pair.public.encoded,
            )

            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(tbs)
                sign()
            }
            val certificate = Der.sequence(
                tbs,
                algorithm,
                Der.tlv(0x03, byteArrayOf(0) + signature),
            )
            return SigningKey(pair.private, certificate)
        }

        private fun decodePrivateKey(der: ByteArray): PrivateKey {
            val spec = java.security.spec.PKCS8EncodedKeySpec(der)
            return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec)
        }
    }
}

/** 最小 DER 编码器，仅覆盖自签证书需要的结构。 */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(length(content.size))
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    fun integer(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return tlv(0x02, bytes)
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var value = parts[i]
            val stack = ArrayList<Int>()
            do {
                stack.add((value and 0x7F).toInt())
                value = value shr 7
            } while (value > 0)
            for (j in stack.indices.reversed()) {
                out.write(stack[j] or (if (j != 0) 0x80 else 0))
            }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun utf8(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    fun name(commonName: String): ByteArray = sequence(
        tlv(0x31, sequence(oid("2.5.4.3"), utf8(commonName))),
    )

    fun algorithmSha256Rsa(): ByteArray = sequence(oid("1.2.840.113549.1.1.11"), tlv(0x05, ByteArray(0)))

    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        size < 0x10000 -> byteArrayOf(
            0x82.toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        )
        else -> byteArrayOf(
            0x83.toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        )
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
