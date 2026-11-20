package dev.muv.patcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 绑定 Shizuku UserService，拿到以 shell 身份执行的文件接口。 */
object ShizukuFs {
    private val mutex = Mutex()
    private var service: IRemoteFs? = null

    suspend fun <T> withFs(context: Context, block: suspend (IRemoteFs) -> T): T = mutex.withLock {
        val current = service?.takeIf { alive(it) }
            ?: withTimeout(15_000) { connect(context.applicationContext) }.also { service = it }
        block(current)
    }

    private fun alive(fs: IRemoteFs): Boolean =
        runCatching { fs.asBinder().pingBinder() }.getOrDefault(false)

    private suspend fun connect(context: Context): IRemoteFs = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val fs = binder?.takeIf { it.pingBinder() }?.let { IRemoteFs.Stub.asInterface(it) }
                if (fs == null) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Shizuku 服务连接失败"))
                    }
                    return
                }
                if (continuation.isActive) continuation.resume(fs)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, RemoteFsService::class.java.name),
        )
            .processNameSuffix("fs")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, connection)
    }
}
