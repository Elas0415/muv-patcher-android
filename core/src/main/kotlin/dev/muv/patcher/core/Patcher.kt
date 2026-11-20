package dev.muv.patcher.core

import java.io.File

/** 注入流水线：校验 → 重写 → 签名 → 自检。 */
object Patcher {
    data class Result(
        val output: File,
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val entries: Int,
        val replaced: Int,
        val added: Int,
        val certificateSha256: String,
        val warnings: List<String>,
    )

    fun isPatched(apk: File): Boolean =
        ZipRewriter.listEntries(apk).any { it.startsWith("assets/LemonLoader/") }

    fun patch(
        source: File,
        payload: Payload,
        output: File,
        key: SigningKey,
        force: Boolean = false,
        onLog: (String) -> Unit = {},
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result {
        require(source.isFile) { "源 APK 不存在" }
        val info = Axml.parse(source)
        onLog("目标：${info.pkg} ${info.versionName}（versionCode ${info.versionCode}）")

        if (isPatched(source) && !force) {
            throw IllegalStateException("该 APK 已注入过 LemonLoader；如需重新注入请开启“允许覆盖”")
        }

        val warnings = ArrayList<String>()
        if (payload.versionCode != 0 && payload.versionCode != info.versionCode) {
            warnings += "物料来自 versionCode ${payload.versionCode}，目标是 ${info.versionCode}；" +
                "interop 按游戏版本生成，跨版本可能启动失败"
        }

        val abi = "arm64-v8a"
        val sources = payload.sources(abi)
        val existing = ZipRewriter.listEntries(source).toHashSet()
        val overrides = sources.filterKeys { it in existing }
        val additions = sources.filterKeys { it !in existing }
        if ("lib/$abi/libmain.so" !in overrides) {
            throw IllegalStateException("物料中没有与目标 ABI 匹配的 libmain.so")
        }
        warnings.forEach { onLog("警告：$it") }

        val free = output.parentFile?.usableSpace ?: 0
        val needed = source.length() * 2 + 200L * 1024 * 1024
        if (free in 1 until needed) {
            throw IllegalStateException("存储空间不足，至少需要 ${needed / 1024 / 1024} MB")
        }

        val temp = File(output.absoluteFile.parentFile, output.name + ".unsigned")
        try {
            onProgress(0.02f, "重写 APK")
            val stats = ZipRewriter.rewrite(source, temp, overrides, additions) { done, total ->
                onProgress(0.05f + 0.55f * done / total.coerceAtLeast(1), "重写 APK")
            }
            onLog("重写完成：${stats.entries} 个条目（替换 ${stats.replaced} / 新增 ${stats.added}）")

            onProgress(0.62f, "计算签名摘要")
            ApkSigner.sign(temp, output, key) { fraction ->
                onProgress(0.62f + 0.3f * fraction, "写入 v2 签名")
            }

            onProgress(0.95f, "校验产物")
            val verification = ApkSigner.verify(output)
            if (!verification.ok) throw IllegalStateException("签名自检失败：${verification.error}")
            val misaligned = ZipRewriter.alignmentReport(output)
            if (misaligned.isNotEmpty()) {
                val first = misaligned.first()
                throw IllegalStateException("对齐失败：${first.name}（${first.remainder} % ${first.align}）")
            }

            onProgress(1f, "完成")
            onLog("签名：v2 通过 ${verification.certSha256?.take(16)}…")
            onLog("对齐：通过")
            return Result(
                output = output,
                packageName = info.pkg,
                versionName = info.versionName,
                versionCode = info.versionCode,
                entries = stats.entries,
                replaced = stats.replaced,
                added = stats.added,
                certificateSha256 = verification.certSha256 ?: "",
                warnings = warnings,
            )
        } finally {
            temp.delete()
        }
    }
}
