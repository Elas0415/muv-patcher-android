package dev.muv.patcher.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipFile

/** 流式 ZIP 重写：原样搬运已压缩数据，只压缩新增/替换条目，并在写入时对齐。 */
object ZipRewriter {
    private const val CHUNK = 1 shl 20
    private const val EXTRA_ZIP64 = 0x0001
    private const val EXTRA_ALIGN = 0xD935
    private const val FLAG_DATA_DESCRIPTOR = 0x0008

    data class Stats(val entries: Int, val replaced: Int, val added: Int, val bytes: Long)
    data class Misaligned(val name: String, val remainder: Int, val align: Int)

    fun interface EntrySource {
        fun open(): InputStream
    }

    class FileSource(private val file: File) : EntrySource {
        override fun open(): InputStream = file.inputStream()
    }

    private data class Entry(
        val name: String,
        val versionMade: Int,
        val versionNeeded: Int,
        val flags: Int,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val csize: Long,
        val usize: Long,
        val internalAttr: Int,
        val externalAttr: Long,
        val localOffset: Long,
        val extra: ByteArray,
        val comment: ByteArray,
    )

    private data class Written(val entry: Entry, val offset: Long)

    fun listEntries(file: File): List<String> = ZipFile(file).use { zip ->
        zip.entries().toList().map { it.name }
    }

    fun rewrite(
        src: File,
        dst: File,
        overrides: Map<String, EntrySource>,
        additions: Map<String, EntrySource>,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Stats {
        val pending = HashMap(overrides)
        RandomAccessFile(src, "r").use { raf ->
            val eocdOffset = findEocd(raf)
            raf.seek(eocdOffset)
            val eocd = ByteArray((raf.length() - eocdOffset).toInt())
            raf.readFully(eocd)
            val cdSize = u32(eocd, 12)
            val cdOffset = u32(eocd, 16)
            val entries = parseCentralDirectory(raf, cdOffset, cdSize)
            require(entries.isNotEmpty()) { "APK 中央目录为空" }

            val counter = CountingOutputStream(BufferedOutputStream(dst.outputStream(), CHUNK))
            val written = ArrayList<Written>(entries.size + additions.size)

            entries.forEachIndexed { index, entry ->
                val start = counter.count
                val source = pending.remove(entry.name)
                val stored = if (source != null) {
                    writeCompressed(counter, entry.name, source, start, entry.time, entry.date)
                } else {
                    copyRaw(raf, counter, entry, start)
                    entry
                }
                written += Written(stored, start)
                if (onProgress != null && index % 100 == 0) onProgress(index, entries.size)
            }
            require(pending.isEmpty()) { "有替换条目在源 APK 中不存在：${pending.keys.first()}" }

            val dosDate = ((2026 - 1980) shl 9) or (1 shl 5) or 1
            var added = 0
            additions.forEach { (name, source) ->
                val start = counter.count
                val stored = writeCompressed(counter, name, source, start, 0, dosDate)
                written += Written(stored, start)
                added++
                onProgress?.invoke(entries.size, entries.size)
            }

            val cdStart = counter.count
            written.forEach { (entry, offset) -> writeCentralDirectory(counter, entry, offset) }
            val cdNewSize = counter.count - cdStart

            val newEocd = eocd.copyOf()
            putU16(newEocd, 8, written.size and 0xFFFF)
            putU16(newEocd, 10, written.size and 0xFFFF)
            putU32(newEocd, 12, cdNewSize)
            putU32(newEocd, 16, cdStart)
            counter.write(newEocd)
            counter.flush()

            return Stats(written.size, overrides.size, added, dst.length())
        }
    }

    fun alignmentReport(file: File): List<Misaligned> {
        RandomAccessFile(file, "r").use { raf ->
            val eocdOffset = findEocd(raf)
            raf.seek(eocdOffset)
            val eocd = ByteArray((raf.length() - eocdOffset).toInt())
            raf.readFully(eocd)
            val entries = parseCentralDirectory(raf, u32(eocd, 16), u32(eocd, 12))
            val bad = ArrayList<Misaligned>()
            for (entry in entries) {
                val align = requiredAlign(entry.name, entry.method)
                if (align == 0) continue
                val dataOffset = localDataOffset(raf, entry)
                val remainder = (dataOffset % align).toInt()
                if (remainder != 0) bad += Misaligned(entry.name, remainder, align)
            }
            return bad
        }
    }

    private fun requiredAlign(name: String, method: Int): Int = when {
        method != 0 -> 0
        name.startsWith("lib/") && name.endsWith(".so") -> 16384
        else -> 4
    }

    private fun writeAlign(name: String, method: Int): Int = when {
        method == 0 && name.startsWith("lib/") && name.endsWith(".so") -> 16384
        else -> 4
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val size = raf.length()
        val window = minOf(size, 66_000L).toInt()
        raf.seek(size - window)
        val tail = ByteArray(window)
        raf.readFully(tail)
        var i = window - 22
        while (i >= 0) {
            if (u32(tail, i) == 0x06054b50L) {
                val commentLength = u16(tail, i + 20)
                if (i + 22 + commentLength == window) return size - window + i
            }
            i--
        }
        throw IllegalArgumentException("找不到 EOCD，文件可能损坏或使用 ZIP64")
    }

    private fun parseCentralDirectory(raf: RandomAccessFile, offset: Long, size: Long): List<Entry> {
        raf.seek(offset)
        val data = ByteArray(size.toInt())
        raf.readFully(data)
        val entries = ArrayList<Entry>()
        var pos = 0
        while (pos + 46 <= data.size) {
            if (u32(data, pos) != 0x02014b50L) break
            val nameLength = u16(data, pos + 28)
            val extraLength = u16(data, pos + 30)
            val commentLength = u16(data, pos + 32)
            entries += Entry(
                name = String(data, pos + 46, nameLength, Charsets.UTF_8),
                versionMade = u16(data, pos + 4),
                versionNeeded = u16(data, pos + 6),
                flags = u16(data, pos + 8),
                method = u16(data, pos + 10),
                time = u16(data, pos + 12),
                date = u16(data, pos + 14),
                crc = u32(data, pos + 16),
                csize = u32(data, pos + 20),
                usize = u32(data, pos + 24),
                internalAttr = u16(data, pos + 36),
                externalAttr = u32(data, pos + 38),
                localOffset = u32(data, pos + 42),
                extra = data.copyOfRange(pos + 46 + nameLength, pos + 46 + nameLength + extraLength),
                comment = data.copyOfRange(
                    pos + 46 + nameLength + extraLength,
                    pos + 46 + nameLength + extraLength + commentLength,
                ),
            )
            pos += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    private fun copyRaw(raf: RandomAccessFile, out: OutputStream, entry: Entry, position: Long) {
        raf.seek(entry.localOffset)
        val local = ByteArray(30)
        raf.readFully(local)
        require(u32(local, 0) == 0x04034b50L) { "条目 ${entry.name} 的本地头无效" }
        val nameLength = u16(local, 26)
        val extraLength = u16(local, 28)
        val dataOffset = entry.localOffset + 30 + nameLength + extraLength

        val name = entry.name.toByteArray(Charsets.UTF_8)
        val extra = padExtra(cleanExtra(entry.extra), position + 30 + name.size, writeAlign(entry.name, entry.method))
        val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x04034b50)
        header.putShort(entry.versionNeeded.toShort())
        header.putShort((entry.flags and FLAG_DATA_DESCRIPTOR.inv()).toShort())
        header.putShort(entry.method.toShort())
        header.putShort(entry.time.toShort())
        header.putShort(entry.date.toShort())
        header.putInt(entry.crc.toInt())
        header.putInt(entry.csize.toInt())
        header.putInt(entry.usize.toInt())
        header.putShort(name.size.toShort())
        header.putShort(extra.size.toShort())
        out.write(header.array())
        out.write(name)
        out.write(extra)

        raf.seek(dataOffset)
        var remaining = entry.csize
        val buffer = ByteArray(CHUNK)
        while (remaining > 0) {
            val read = raf.read(buffer, 0, minOf(CHUNK.toLong(), remaining).toInt())
            if (read <= 0) throw IllegalStateException("条目 ${entry.name} 数据被截断")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeCompressed(
        out: OutputStream,
        name: String,
        source: EntrySource,
        position: Long,
        time: Int,
        date: Int,
    ): Entry {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32()
        var usize = 0L
        var csize = 0L
        val deflater = Deflater(6, true)
        val compressed = java.io.ByteArrayOutputStream()
        val inputBuffer = ByteArray(CHUNK)
        val outputBuffer = ByteArray(CHUNK)
        source.open().use { input ->
            while (true) {
                val read = input.read(inputBuffer)
                if (read <= 0) break
                crc.update(inputBuffer, 0, read)
                usize += read
                deflater.setInput(inputBuffer, 0, read)
                while (!deflater.needsInput()) {
                    val produced = deflater.deflate(outputBuffer)
                    if (produced > 0) {
                        compressed.write(outputBuffer, 0, produced)
                        csize += produced
                    }
                }
            }
        }
        deflater.finish()
        while (!deflater.finished()) {
            val produced = deflater.deflate(outputBuffer)
            if (produced > 0) {
                compressed.write(outputBuffer, 0, produced)
                csize += produced
            }
        }
        deflater.end()
        require(usize <= 0xFFFFFFFFL && csize <= 0xFFFFFFFFL) { "条目过大，需要 ZIP64" }

        val extra = padExtra(ByteArray(0), position + 30 + nameBytes.size, writeAlign(name, 8))
        val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x04034b50)
        header.putShort(20)
        header.putShort(0x0800.toShort())
        header.putShort(8)
        header.putShort(time.toShort())
        header.putShort(date.toShort())
        header.putInt(crc.value.toInt())
        header.putInt(csize.toInt())
        header.putInt(usize.toInt())
        header.putShort(nameBytes.size.toShort())
        header.putShort(extra.size.toShort())
        out.write(header.array())
        out.write(nameBytes)
        out.write(extra)
        compressed.writeTo(out)

        return Entry(
            name = name, versionMade = 20, versionNeeded = 20, flags = 0x0800, method = 8,
            time = time, date = date, crc = crc.value, csize = csize, usize = usize,
            internalAttr = 0, externalAttr = (0x1A4L shl 16), localOffset = position,
            extra = ByteArray(0), comment = ByteArray(0),
        )
    }

    private fun writeCentralDirectory(out: OutputStream, entry: Entry, offset: Long) {
        val name = entry.name.toByteArray(Charsets.UTF_8)
        val extra = cleanExtra(entry.extra)
        val header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x02014b50)
        header.putShort(entry.versionMade.toShort())
        header.putShort(entry.versionNeeded.toShort())
        header.putShort((entry.flags and FLAG_DATA_DESCRIPTOR.inv()).toShort())
        header.putShort(entry.method.toShort())
        header.putShort(entry.time.toShort())
        header.putShort(entry.date.toShort())
        header.putInt(entry.crc.toInt())
        header.putInt(entry.csize.toInt())
        header.putInt(entry.usize.toInt())
        header.putShort(name.size.toShort())
        header.putShort(extra.size.toShort())
        header.putShort(0)
        header.putShort(0)
        header.putShort(entry.internalAttr.toShort())
        header.putInt(entry.externalAttr.toInt())
        header.putInt(offset.toInt())
        out.write(header.array())
        out.write(name)
        out.write(extra)
    }

    private fun cleanExtra(extra: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        while (pos + 4 <= extra.size) {
            val id = u16(extra, pos)
            val length = u16(extra, pos + 2)
            val end = pos + 4 + length
            if (end > extra.size) break
            if (id != EXTRA_ZIP64 && id != EXTRA_ALIGN) out.write(extra, pos, end - pos)
            pos = end
        }
        return out.toByteArray()
    }

    private fun padExtra(extra: ByteArray, offsetWithoutPadding: Long, align: Int): ByteArray {
        var pad = ((align - ((offsetWithoutPadding + extra.size) % align)) % align).toInt()
        if (pad != 0 && pad < 4) pad += align
        if (pad == 0) return extra
        val out = ByteArray(extra.size + pad)
        extra.copyInto(out)
        putU16(out, extra.size, EXTRA_ALIGN)
        putU16(out, extra.size + 2, pad - 4)
        return out
    }

    private fun localDataOffset(raf: RandomAccessFile, entry: Entry): Long {
        raf.seek(entry.localOffset)
        val header = ByteArray(30)
        raf.readFully(header)
        return entry.localOffset + 30 + u16(header, 26) + u16(header, 28)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.close()
        }
    }
}
