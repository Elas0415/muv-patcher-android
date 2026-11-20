package dev.muv.patcher.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** 解析二进制 AndroidManifest.xml，替代 aapt2。 */
object Axml {
    data class ManifestInfo(
        val pkg: String,
        val versionCode: Int,
        val versionName: String,
        val minSdk: Int,
        val targetSdk: Int,
    )

    private const val RES_STRING_POOL = 0x0001
    private const val RES_START_ELEMENT = 0x0102
    private const val NO_ENTRY = -1

    fun parse(apk: File): ManifestInfo = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml")
            ?: throw IllegalArgumentException("APK 中没有 AndroidManifest.xml")
        parseBytes(zip.getInputStream(entry).use { it.readBytes() })
    }

    fun parseBytes(data: ByteArray): ManifestInfo {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size >= 8) { "manifest 太短" }
        require(buffer.short.toInt() and 0xFFFF == 0x0003) { "不是二进制 XML" }

        var offset = buffer.short.toInt() and 0xFFFF
        var pool: List<String> = emptyList()
        val elements = ArrayList<Element>()

        while (offset + 8 <= data.size) {
            buffer.position(offset)
            val type = buffer.short.toInt() and 0xFFFF
            val headerSize = buffer.short.toInt() and 0xFFFF
            val chunkSize = buffer.int
            if (chunkSize < 8) break

            when (type) {
                RES_STRING_POOL -> pool = readPool(buffer, offset, headerSize)
                RES_START_ELEMENT -> {
                    val nameIndex = data.intAt(offset + 20)
                    val attrStart = data.shortAt(offset + 24)
                    val attrSize = data.shortAt(offset + 26)
                    val attrCount = data.shortAt(offset + 28)
                    var pos = offset + headerSize + attrStart
                    val attrs = HashMap<String, Any?>()
                    repeat(attrCount) {
                        if (pos + 20 <= data.size) {
                            val nameIdx = data.intAt(pos + 4)
                            val rawIdx = data.intAt(pos + 8)
                            val valueType = data[pos + 15].toInt() and 0xFF
                            val valueData = data.intAt(pos + 16)
                            val name = pool.getOrElse(nameIdx) { "" }
                            attrs[name] = when {
                                rawIdx != NO_ENTRY && rawIdx in pool.indices -> pool[rawIdx]
                                valueType == 0x03 && valueData in pool.indices -> pool[valueData]
                                valueType == 0x12 -> valueData != 0
                                else -> valueData
                            }
                        }
                        pos += attrSize
                    }
                    elements += Element(pool.getOrElse(nameIndex) { "" }, attrs)
                }
            }
            offset += chunkSize
        }

        val manifest = elements.firstOrNull { it.name == "manifest" }
            ?: throw IllegalArgumentException("找不到 manifest 根节点")
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
        return ManifestInfo(
            pkg = manifest.attrs["package"] as? String ?: "",
            versionCode = (manifest.attrs["versionCode"] as? Number)?.toInt() ?: 0,
            versionName = manifest.attrs["versionName"] as? String ?: "",
            minSdk = (usesSdk?.attrs?.get("minSdkVersion") as? Number)?.toInt() ?: 0,
            targetSdk = (usesSdk?.attrs?.get("targetSdkVersion") as? Number)?.toInt() ?: 0,
        )
    }

    private data class Element(val name: String, val attrs: Map<String, Any?>)

    private fun readPool(buffer: ByteBuffer, offset: Int, headerSize: Int): List<String> {
        buffer.position(offset + 8)
        val count = buffer.int
        buffer.int // styleCount
        val flags = buffer.int
        val stringsStart = buffer.int
        buffer.int // stylesStart
        val utf8 = (flags and 0x100) != 0
        val offsets = IntArray(count)
        buffer.position(offset + headerSize)
        for (i in 0 until count) offsets[i] = buffer.int

        val base = offset + stringsStart
        val result = ArrayList<String>(count)
        for (rel in offsets) {
            var pos = base + rel
            if (utf8) {
                var b: Int
                var shift = 0
                do {
                    b = buffer.get(pos).toInt() and 0xFF
                    pos++
                    shift += 7
                } while (b and 0x80 != 0)
                var byteLength = 0
                shift = 0
                do {
                    b = buffer.get(pos).toInt() and 0xFF
                    pos++
                    byteLength = byteLength or ((b and 0x7F) shl shift)
                    shift += 7
                } while (b and 0x80 != 0)
                val bytes = ByteArray(byteLength)
                for (i in 0 until byteLength) bytes[i] = buffer.get(pos + i)
                result += String(bytes, Charsets.UTF_8)
            } else {
                var length = buffer.getShort(pos).toInt() and 0xFFFF
                pos += 2
                if (length and 0x8000 != 0) {
                    length = ((length and 0x7FFF) shl 16) or (buffer.getShort(pos).toInt() and 0xFFFF)
                    pos += 2
                }
                val chars = CharArray(length)
                for (i in 0 until length) chars[i] = buffer.getChar(pos + i * 2)
                result += String(chars)
            }
        }
        return result
    }

    private fun ByteArray.shortAt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.intAt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
