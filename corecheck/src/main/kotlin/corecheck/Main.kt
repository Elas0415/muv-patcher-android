package corecheck

import dev.muv.patcher.core.Patcher
import dev.muv.patcher.core.Payload
import dev.muv.patcher.core.SigningKey
import dev.muv.patcher.core.ZipRewriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun main(args: Array<String>) {
    if ("--selftest" in args) {
        selftest()
        if (args.size < 3) return
    }
    if (args.size < 3) {
        println("用法: corecheck <source.apk> <payload.zip> <out.apk> [keyDir] [--force] [--selftest]")
        return
    }

    val source = File(args[0])
    val payloadFile = File(args[1])
    val output = File(args[2])
    val keyDir = File(args.getOrElse(3) { "keys" })
    val force = "--force" in args

    output.parentFile?.mkdirs()
    val key = SigningKey.loadOrCreate(keyDir)
    println("签名身份: ${key.fingerprint}")

    Payload.open(payloadFile).use { payload ->
        val started = System.currentTimeMillis()
        val result = Patcher.patch(
            source = source,
            payload = payload,
            output = output,
            key = key,
            force = force,
            onLog = { println("  $it") },
            onProgress = { fraction, text ->
                print("\r  $text ${(fraction * 100).toInt()}%          ")
            },
        )
        println()
        println("完成: ${result.output} (${result.output.length() / 1_000_000} MB)")
        println("条目 ${result.entries} / 替换 ${result.replaced} / 新增 ${result.added}")
        println("耗时 ${(System.currentTimeMillis() - started) / 1000} s")
    }
}

private fun selftest() {
    val dir = File("build/selftest").apply { mkdirs() }
    val src = File(dir, "mini.zip")
    val out = File(dir, "mini-out.zip")
    ZipOutputStream(src.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("a.txt")); zip.write("hello".toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("b.txt")); zip.write(ByteArray(100_000) { 7 }); zip.closeEntry()
        zip.putNextEntry(ZipEntry("lib/arm64-v8a/libmain.so")); zip.write(ByteArray(4096)); zip.closeEntry()
    }
    val added = File(dir, "added.bin").apply { writeBytes(ByteArray(50_000) { 3 }) }
    val replaced = File(dir, "replaced.txt").apply { writeText("replaced") }
    val stats = ZipRewriter.rewrite(
        src, out,
        mapOf("a.txt" to ZipRewriter.FileSource(replaced)),
        mapOf("c.bin" to ZipRewriter.FileSource(added)),
    )
    ZipFile(out).use { zip ->
        check(zip.getEntry("a.txt") != null && zip.getEntry("c.bin") != null) { "条目丢失" }
        check(zip.getInputStream(zip.getEntry("a.txt")).readBytes().toString(Charsets.UTF_8) == "replaced")
        check(zip.getInputStream(zip.getEntry("b.txt")).readBytes().size == 100_000)
    }
    check(ZipRewriter.alignmentReport(out).isEmpty()) { "对齐失败" }
    println("selftest 通过: $stats")
}
