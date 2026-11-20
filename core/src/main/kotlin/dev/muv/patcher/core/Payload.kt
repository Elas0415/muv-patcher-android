package dev.muv.patcher.core

import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 注入物料包（bundle.zip）：直接以流方式读取，无需解压到磁盘。 */
class Payload private constructor(
    private val zip: ZipFile,
    val meta: Map<String, Any?>,
) : Closeable {
    private val entries: Map<String, ZipEntry> = zip.entries().toList().associateBy { it.name }

    val versionCode: Int
        get() = ((meta["source"] as? Map<*, *>)?.get("version_code") as? Number)?.toInt() ?: 0

    val versionName: String
        get() = ((meta["source"] as? Map<*, *>)?.get("version_name") as? String) ?: ""

    val loaderVersion: String
        get() = ((meta["loader"] as? Map<*, *>)?.get("version") as? String) ?: ""

    val packageName: String
        get() = ((meta["source"] as? Map<*, *>)?.get("package") as? String) ?: ""

    fun sources(abi: String): Map<String, ZipRewriter.EntrySource> {
        val result = LinkedHashMap<String, ZipRewriter.EntrySource>()
        val host = "lib/$abi/libmain.so"
        for ((name, entry) in entries) {
            if (name.startsWith("assets/LemonLoader/") || name == host) {
                result[name] = Source(entry)
            }
        }
        return result
    }

    fun fileCount(): Int = entries.size

    override fun close() {
        zip.close()
    }

    private inner class Source(private val entry: ZipEntry) : ZipRewriter.EntrySource {
        override fun open() = zip.getInputStream(entry)
    }

    companion object {
        fun open(file: File): Payload {
            val zip = ZipFile(file)
            val metaEntry = zip.getEntry("bundle.json")
                ?: throw IllegalArgumentException("物料包缺少 bundle.json")
            val text = zip.getInputStream(metaEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            @Suppress("UNCHECKED_CAST")
            val meta = Json.parse(text) as? Map<String, Any?>
                ?: throw IllegalArgumentException("bundle.json 格式无效")
            return Payload(zip, meta)
        }
    }
}
