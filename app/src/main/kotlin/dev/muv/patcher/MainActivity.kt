package dev.muv.patcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.muv.patcher.core.Axml
import dev.muv.patcher.core.Patcher
import dev.muv.patcher.core.Payload
import dev.muv.patcher.core.SigningKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var pickButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var installButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var infoView: TextView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var forceCheck: CheckBox
    private lateinit var checkButton: MaterialButton
    private lateinit var applyButton: MaterialButton
    private lateinit var translationStatus: TextView

    private var sourceFile: File? = null
    private val outputFile: File by lazy { File(cacheDir, "output.apk") }
    private var workJob: Job? = null
    private var busy = false
    private var lastPercent = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingPlan: TranslationUpdater.Plan? = null
    private var pendingCheck = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST && grantResult == PackageManager.PERMISSION_GRANTED && pendingCheck) {
            pendingCheck = false
            checkUpdates()
        } else if (requestCode == SHIZUKU_REQUEST) {
            translationStatus.text = "未获得 Shizuku 权限"
        }
    }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importApk(uri)
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
            if (uri != null) exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickButton = findViewById(R.id.pick)
        startButton = findViewById(R.id.start)
        installButton = findViewById(R.id.install)
        exportButton = findViewById(R.id.export)
        infoView = findViewById(R.id.info)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        progressBar = findViewById(R.id.progress)
        forceCheck = findViewById(R.id.force)
        checkButton = findViewById(R.id.check_update)
        applyButton = findViewById(R.id.apply_update)
        translationStatus = findViewById(R.id.translation_status)

        pickButton.setOnClickListener {
            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }
        startButton.setOnClickListener { startPatch() }
        installButton.setOnClickListener { installResult() }
        exportButton.setOnClickListener {
            createDocument.launch("muv_patched.apk")
        }
        checkButton.setOnClickListener { checkUpdates() }
        applyButton.setOnClickListener { applyUpdates() }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        workJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ---------- 翻译更新 ----------
    private fun checkUpdates() {
        if (busy) return
        if (!ensureShizuku()) return
        busy = true
        setBusy(true)
        translationStatus.text = "正在检查更新…"
        workJob = lifecycleScope.launch {
            try {
                translationStatus.text = "连接 Shizuku…"
                val plan = withContext(Dispatchers.IO) {
                    ShizukuFs.withFs(this@MainActivity) { fs ->
                        if (!fs.exists(TranslationUpdater.TARGET)) throw MissingDirectoryException()
                        TranslationUpdater.buildPlan(fs) { stage ->
                            runOnUiThread { translationStatus.text = "$stage…" }
                        }
                    }
                }
                log("翻译源：${TranslationUpdater.activeSourceName}")
                pendingPlan = plan
                if (plan.isEmpty) {
                    translationStatus.text = "已是最新（远端 ${plan.remoteScenes} 个场景）"
                    applyButton.isEnabled = false
                } else {
                    translationStatus.text =
                        "可更新 ${plan.entries.size} 个文件（远端 ${plan.remoteScenes} 个场景）"
                    applyButton.isEnabled = true
                }
                log("翻译：${translationStatus.text}")
            } catch (e: MissingDirectoryException) {
                pendingPlan = null
                translationStatus.text = "未找到游戏的翻译目录"
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("请先启动一次游戏")
                    .setMessage(
                        "未找到目录：\n${TranslationUpdater.TARGET}\n\n" +
                            "请先安装并启动一次已注入的版本，等 MelonLoader 解包完成、进入游戏后，再回来检查更新。",
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            } catch (e: Exception) {
                pendingPlan = null
                translationStatus.text = "检查失败：${e.message}"
                log("检查更新失败：${e.message}")
            } finally {
                busy = false
                setBusy(false)
            }
        }
    }

    private fun applyUpdates() {
        val plan = pendingPlan ?: return
        if (busy) return
        busy = true
        setBusy(true)
        translationStatus.text = "正在下载…"
        workJob = lifecycleScope.launch {
            try {
                val started = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    ShizukuFs.withFs(this@MainActivity) { fs ->
                        TranslationUpdater.apply(fs, plan) { done, total, label ->
                            if (done % 10 == 0 || done == total) {
                                runOnUiThread {
                                    setProgress(
                                        done.toFloat() / total.coerceAtLeast(1),
                                        "更新 $label（$done/$total）",
                                    )
                                }
                            }
                        }
                    }
                }
                val seconds = (System.currentTimeMillis() - started) / 1000
                translationStatus.text = "已更新 ${plan.entries.size} 个文件（${seconds} 秒），重启游戏后生效"
                log("翻译更新完成：${plan.entries.size} 个文件，耗时 ${seconds} 秒")
                pendingPlan = null
                Toast.makeText(this@MainActivity, "更新完成，重启游戏生效", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                translationStatus.text = "更新失败：${e.message}"
                log("翻译更新失败：${e.message}")
            } finally {
                busy = false
                setBusy(false)
            }
        }
    }

    private fun ensureShizuku(): Boolean {
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
        if (!running) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Shizuku 未运行")
                .setMessage(
                    "翻译写入需要 Shizuku（以 shell 身份访问 Android/data）。\n\n" +
                        "请安装并启动 Shizuku，通过无线调试授权后回到本页重试。",
                )
                .setPositiveButton("知道了", null)
                .show()
            translationStatus.text = "需要 Shizuku"
            return false
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        if (!granted) {
            pendingCheck = true
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            translationStatus.text = "等待 Shizuku 授权…"
            return false
        }
        return true
    }

    private class MissingDirectoryException : IOException("未找到翻译目录")

    // ---------- 选择与导入 ----------
    private fun importApk(uri: Uri) {
        if (busy) return
        setBusy(true)
        statusView.text = "正在读取 APK…"
        log("读取：$uri")
        workJob = lifecycleScope.launch {
            try {
                val target = File(cacheDir, "source.apk")
                val total = querySize(uri)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 20)
                            var copied = 0L
                            var last = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) {
                                    val percent = (copied * 100 / total).toInt()
                                    if (percent != last) {
                                        last = percent
                                        setProgress(percent / 100f, "导入 APK $percent%")
                                    }
                                }
                            }
                        }
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                val info = withContext(Dispatchers.IO) { Axml.parse(target) }
                sourceFile = target
                val patched = withContext(Dispatchers.IO) { Patcher.isPatched(target) }
                infoView.text = buildString {
                    append("${info.pkg}\n")
                    append("${info.versionName}（versionCode ${info.versionCode}） · ")
                    append("${target.length() / 1_000_000} MB")
                    if (patched) append(" · 已注入")
                }
                startButton.isEnabled = true
                log("APK：${info.pkg} ${info.versionName}")
                if (patched) log("提示：该 APK 已包含 LemonLoader，需要勾选“允许覆盖”")
                statusView.text = "就绪"
                setProgress(0f, "就绪")
            } catch (e: Exception) {
                fail("读取失败", e)
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- 注入 ----------
    private fun startPatch() {
        val source = sourceFile ?: return
        if (busy) return
        setBusy(true)
        progressBar.progress = 0
        lastPercent = -1
        val force = forceCheck.isChecked
        workJob = lifecycleScope.launch {
            acquireWakeLock()
            try {
                log("—— 开始注入 ——")
                val payloadFile = withContext(Dispatchers.IO) { ensurePayload() }
                withContext(Dispatchers.IO) {
                    Payload.open(payloadFile).use { payload ->
                        val key = SigningKey.loadOrCreate(File(filesDir, "keys"))
                        log("物料 ${payload.loaderVersion}（来源 versionCode ${payload.versionCode}）")
                        log("签名身份 ${key.fingerprint.take(16)}…")
                        val result = Patcher.patch(
                            source = source,
                            payload = payload,
                            output = outputFile,
                            key = key,
                            force = force,
                            onLog = { message -> runOnUiThread { log(message) } },
                            onProgress = { fraction, text ->
                                runOnUiThread { setProgress(fraction, text) }
                            },
                        )
                        runOnUiThread {
                            log("完成：${result.output.name}（${result.output.length() / 1_000_000} MB）")
                            log("条目 ${result.entries} · 替换 ${result.replaced} · 新增 ${result.added}")
                            installButton.isEnabled = true
                            exportButton.isEnabled = true
                            statusView.text = "完成，可安装"
                        }
                    }
                }
            } catch (e: Exception) {
                fail("注入失败", e)
            } finally {
                setBusy(false)
                releaseWakeLock()
            }
        }
    }

    private fun ensurePayload(): File {
        val target = File(filesDir, "payload.zip")
        if (target.isFile && target.length() > 1_000_000) return target
        val temp = File(filesDir, "payload.zip.part")
        assets.open("payload.zip").use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        }
        if (target.exists()) target.delete()
        temp.renameTo(target)
        return target
    }

    // ---------- 安装与导出 ----------
    private fun installResult() {
        if (!outputFile.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的安装器", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportTo(uri: Uri) {
        if (!outputFile.isFile) return
        workJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        outputFile.inputStream().use { input -> input.copyTo(output, 1 shl 20) }
                    } ?: throw IllegalStateException("无法写入所选位置")
                }
                log("已导出到：$uri")
                Toast.makeText(this@MainActivity, "导出完成", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                fail("导出失败", e)
            }
        }
    }

    // ---------- 工具 ----------
    private fun querySize(uri: Uri): Long = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun setBusy(value: Boolean) {
        busy = value
        pickButton.isEnabled = !value
        startButton.isEnabled = !value && sourceFile != null
        installButton.isEnabled = !value && outputFile.isFile
        exportButton.isEnabled = !value && outputFile.isFile
        checkButton.isEnabled = !value
        applyButton.isEnabled = !value && pendingPlan?.isEmpty == false
    }

    private fun setProgress(fraction: Float, text: String) {
        val percent = (fraction * 1000).toInt()
        if (percent == lastPercent && text != "完成") return
        lastPercent = percent
        progressBar.progress = percent
        statusView.text = text
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$time] $message\n")
    }

    private fun fail(title: String, e: Exception) {
        log("$title：${e.message}")
        statusView.text = title
        Toast.makeText(this, "$title：${e.message}", Toast.LENGTH_LONG).show()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MuvPatcher:patch").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private companion object {
        const val SHIZUKU_REQUEST = 1001
    }
}
