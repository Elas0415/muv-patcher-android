package dev.muv.patcher.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec

/** APK Signature Scheme v2 签名与自校验。 */
object ApkSigner {
    private const val V2_BLOCK_ID = 0x7109871A
    private const val ALG_RSA_PKCS1_SHA256 = 0x0103
    private const val CHUNK = 1 shl 20
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    data class VerifyResult(val ok: Boolean, val certSha256: String? = null, val error: String? = null)

    fun sign(unsigned: File, output: File, key: SigningKey, onProgress: ((Float) -> Unit)? = null) {
        RandomAccessFile(unsigned, "r").use { raf ->
            val size = raf.length()
            val eocdOffset = findEocd(raf)
            val eocd = readAt(raf, eocdOffset, (size - eocdOffset).toInt())
            val cdSize = u32(eocd, 12)
            val cdOffset = u32(eocd, 16)
            val contentsEnd = signingBlockStart(raf, cdOffset)

            val eocdForDigest = eocd.copyOf().also { putU32(it, 16, contentsEnd) }
            val digest = contentDigest(
                raf,
                listOf(0L to contentsEnd, cdOffset to cdOffset + cdSize),
                eocdForDigest,
            )
            onProgress?.invoke(0.6f)

            val block = buildBlock(digest, key)
            output.outputStream().buffered(CHUNK).use { out ->
                copyRange(raf, out, 0, contentsEnd)
                out.write(block)
                copyRange(raf, out, cdOffset, cdOffset + cdSize)
                val newEocd = eocd.copyOf()
                putU32(newEocd, 16, cdOffset + block.size)
                out.write(newEocd)
            }
            onProgress?.invoke(1f)
        }
    }

    fun verify(apk: File): VerifyResult = try {
        RandomAccessFile(apk, "r").use { raf ->
            val size = raf.length()
            val eocdOffset = findEocd(raf)
            val eocd = readAt(raf, eocdOffset, (size - eocdOffset).toInt())
            val cdSize = u32(eocd, 12)
            val cdOffset = u32(eocd, 16)
            val blockStart = signingBlockStart(raf, cdOffset)
            if (blockStart == cdOffset) return VerifyResult(false, error = "未找到 v2 签名块")

            val sizeField = u64(readAt(raf, blockStart, 8), 0)
            val pairs = readAt(raf, blockStart + 8, (sizeField - 8 - MAGIC.size).toInt())

            var pos = 0
            var value: ByteArray? = null
            while (pos + 12 <= pairs.size) {
                val pairLength = u64(pairs, pos)
                val id = u32(pairs, pos + 8).toInt()
                val end = (pos + 8 + pairLength).toInt()
                if (id == V2_BLOCK_ID) {
                    value = pairs.copyOfRange(pos + 12, end)
                    break
                }
                pos = end
            }
            val blockValue = value ?: return VerifyResult(false, error = "签名块不含 v2 签名")

            val signers = readLp(blockValue, 0).first
            val signer = readLp(signers, 0).first

            var cursor = 0
            val signedData = readLp(signer, cursor).first
            cursor += 4 + signedData.size
            val signatures = readLp(signer, cursor).first
            cursor += 4 + signatures.size
            val publicKeyDer = readLp(signer, cursor).first

            val digests = readLp(signedData, 0).first
            val certificates = readLp(signedData, 4 + digests.size).first

            val digestRecord = readLp(digests, 0).first
            val algo = u32(digestRecord, 0).toInt()
            if (algo != ALG_RSA_PKCS1_SHA256) return VerifyResult(false, error = "不支持的摘要算法")
            val storedDigest = readLp(digestRecord, 4).first

            val signatureRecord = readLp(signatures, 0).first
            val signature = readLp(signatureRecord, 4).first
            val certificate = readLp(certificates, 0).first

            val eocdForDigest = eocd.copyOf().also { putU32(it, 16, blockStart) }
            val actual = contentDigest(
                raf,
                listOf(0L to blockStart, cdOffset to cdOffset + cdSize),
                eocdForDigest,
            )
            if (!actual.contentEquals(storedDigest)) return VerifyResult(false, error = "内容摘要不匹配")

            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(signedData)
            if (!verifier.verify(signature)) return VerifyResult(false, error = "签名校验失败")

            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate)
                .joinToString("") { "%02x".format(it) }
            VerifyResult(true, fingerprint)
        }
    } catch (e: Exception) {
        VerifyResult(false, error = e.message ?: e.javaClass.simpleName)
    }

    private fun buildBlock(digest: ByteArray, key: SigningKey): ByteArray {
        val certificateObject = CertificateFactory.getInstance("X.509")
            .generateCertificate(key.certificate.inputStream()) as X509Certificate

        val digests = sequence(listOf(u32(ALG_RSA_PKCS1_SHA256) + lp(digest)))
        val certificates = sequence(listOf(key.certificate))
        val attributes = sequence(emptyList())
        val signedData = digests + certificates + attributes

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.privateKey)
            update(signedData)
            sign()
        }
        val signatures = sequence(listOf(u32(ALG_RSA_PKCS1_SHA256) + lp(signature)))
        val signer = lp(signedData) + signatures + lp(certificateObject.publicKey.encoded)
        val value = sequence(listOf(signer))

        val pairs = u64((4 + value.size).toLong()) + u32(V2_BLOCK_ID) + value
        val size = pairs.size + 8 + MAGIC.size
        return u64(size.toLong()) + pairs + u64(size.toLong()) + MAGIC
    }

    private fun contentDigest(raf: RandomAccessFile, sections: List<Pair<Long, Long>>, eocd: ByteArray): ByteArray {
        val chunks = ArrayList<ByteArray>()
        val buffer = ByteArray(CHUNK)
        for ((start, end) in sections) {
            raf.seek(start)
            var remaining = end - start
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(CHUNK.toLong(), remaining).toInt())
                if (read <= 0) throw IllegalStateException("读取摘要区间失败")
                chunks += hashChunk(buffer, 0, read)
                remaining -= read
            }
        }
        chunks += hashChunk(eocd, 0, eocd.size)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(0x5A.toByte())
        digest.update(u32(chunks.size))
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    private fun hashChunk(data: ByteArray, offset: Int, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(0xA5.toByte())
        digest.update(u32(length))
        digest.update(data, offset, length)
        return digest.digest()
    }

    private fun signingBlockStart(raf: RandomAccessFile, cdOffset: Long): Long {
        if (cdOffset < 32) return cdOffset
        raf.seek(cdOffset - 24)
        val header = ByteArray(24)
        raf.readFully(header)
        if (!header.copyOfRange(8, 24).contentEquals(MAGIC)) return cdOffset
        return cdOffset - 8 - u64(header, 0)
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val size = raf.length()
        val window = minOf(size, 66_000L).toInt()
        raf.seek(size - window)
        val tail = ByteArray(window)
        raf.readFully(tail)
        var i = window - 22
        while (i >= 0) {
            if (u32(tail, i) == 0x06054B50L) {
                val commentLength = u16(tail, i + 20)
                if (i + 22 + commentLength == window) return size - window + i
            }
            i--
        }
        throw IllegalArgumentException("找不到 EOCD")
    }

    private fun copyRange(raf: RandomAccessFile, out: OutputStream, start: Long, end: Long) {
        raf.seek(start)
        var remaining = end - start
        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            val read = raf.read(buffer, 0, minOf(CHUNK.toLong(), remaining).toInt())
            if (read <= 0) throw IllegalStateException("复制区间失败")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        raf.seek(offset)
        val data = ByteArray(length)
        raf.readFully(data)
        return data
    }

    private fun readLp(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val length = u32(data, offset).toInt()
        val start = offset + 4
        return data.copyOfRange(start, start + length) to (start + length)
    }

    private fun lp(payload: ByteArray): ByteArray = u32(payload.size) + payload

    private fun sequence(items: List<ByteArray>): ByteArray {
        val content = ByteArrayOutputStream()
        items.forEach { content.write(lp(it)) }
        return lp(content.toByteArray())
    }

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun u64(data: ByteArray, offset: Int): Long =
        u32(data, offset) or (u32(data, offset + 4) shl 32)

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
