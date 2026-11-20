package dev.muv.patcher

import android.os.ParcelFileDescriptor
import dev.muv.patcher.core.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 从上游仓库拉取翻译，并通过 Shizuku UserService 写入游戏私有目录。 */
object TranslationUpdater {
    const val TARGET =
        "/storage/emulated/0/Android/data/jp.co.fanzagames.muvluv_ggx_app/files/MelonLoader/" +
            "UserData/MuvluvMod/local-translation"

    /** 依次尝试的翻译源，命中后本次会话优先复用。 */
    private val SOURCES = listOf(
        "https://fastly.jsdelivr.net/gh/Chaojie114/mlgg-translation@main/translation/",
        "https://raw.githubusercontent.com/Chaojie114/mlgg-translation/main/translation/",
        "https://gh-proxy.com/https://raw.githubusercontent.com/Chaojie114/mlgg-translation/main/translation/",
    )

    @Volatile
    private var activeSource: String? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private const val PARALLELISM = 6

    val activeSourceName: String
        get() = activeSource?.substringAfter("//")?.substringBefore("/") ?: "未连接"

    data class Entry(val remotePath: String, val label: String)

    data class Plan(
        val entries: List<Entry>,
        val remoteHash: String,
        val localHash: String?,
        val remoteScenes: Int,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    fun readLocalManifest(fs: IRemoteFs): Map<String, Any?>? {
        val text = runCatching { fs.readText("$TARGET/manifest/zh_Hans.json") }.getOrNull()
        if (text.isNullOrBlank()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            Json.parse(text) as? Map<String, Any?>
        }.getOrNull()
    }

    fun buildPlan(fs: IRemoteFs, onStage: (String) -> Unit = {}): Plan {
        onStage("读取本地清单")
        val local = readLocalManifest(fs)
        onStage("下载远端清单")
        val remote = fetchRemoteManifest()
        val entries = ArrayList<Entry>()

        if (local == null || local["names"] != remote["names"]) {
            entries += Entry("names/zh_Hans.json", "人名")
        }
        if (local == null || local["static"] != remote["static"]) {
            entries += Entry("static/zh_Hans.json", "数据表")
        }

        @Suppress("UNCHECKED_CAST")
        val remoteScenes = remote["scenes"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val localScenes = local?.get("scenes") as? Map<String, Any?> ?: emptyMap()
        for ((id, hash) in remoteScenes) {
            if (localScenes[id] != hash) entries += Entry("scenes/$id/zh_Hans.json", "剧情 $id")
        }

        if (local == null || local["hash"] != remote["hash"]) {
            entries += Entry("manifest/zh_Hans.json", "清单")
        }

        return Plan(
            entries = entries,
            remoteHash = remote["hash"] as? String ?: "",
            localHash = local?.get("hash") as? String,
            remoteScenes = remoteScenes.size,
        )
    }

    suspend fun apply(fs: IRemoteFs, plan: Plan, onProgress: (Int, Int, String) -> Unit) {
        val manifest = plan.entries.lastOrNull { it.remotePath == "manifest/zh_Hans.json" }
        val files = plan.entries.filter { it !== manifest }
        val total = plan.entries.size
        val counter = AtomicInteger(0)
        val semaphore = Semaphore(PARALLELISM)

        coroutineScope {
            files.map { entry ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val data = fetch(entry.remotePath)
                        writeFile(fs, entry.remotePath, data)
                        onProgress(counter.incrementAndGet(), total, entry.label)
                    }
                }
            }.awaitAll()
        }

        // 清单最后写入：中途失败时下次仍会重新补齐缺失文件
        if (manifest != null) {
            val data = fetch(manifest.remotePath)
            writeFile(fs, manifest.remotePath, data)
            onProgress(counter.incrementAndGet(), total, manifest.label)
        }
    }

    private fun writeFile(fs: IRemoteFs, relativePath: String, data: ByteArray) {
        ParcelFileDescriptor.AutoCloseOutputStream(fs.openWrite("$TARGET/$relativePath")).use {
            it.write(data)
        }
    }

    private fun fetchRemoteManifest(): Map<String, Any?> {
        val text = String(fetch("manifest/zh_Hans.json"), Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        return Json.parse(text) as? Map<String, Any?> ?: throw IOException("远端清单格式无效")
    }

    private fun fetch(relativePath: String): ByteArray {
        val order = activeSource?.let { hit -> listOf(hit) + SOURCES.filter { it != hit } } ?: SOURCES
        var lastError: Exception? = null
        for (base in order) {
            try {
                val data = request(base + relativePath)
                activeSource = base
                return data
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(
            "无法连接翻译源（已尝试 ${order.size} 个），请检查网络或代理",
            lastError,
        )
    }

    private fun request(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MuvPatcher")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
            return response.body?.bytes() ?: throw IOException("空响应：$url")
        }
    }
}
