package dev.muv.patcher

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Shizuku UserService：由 Shizuku 服务端以 shell 身份实例化，
 * 因此必须是普通类（继承 AIDL Stub），不能是 Android Service。
 */
class RemoteFsService : IRemoteFs.Stub {
    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun openWrite(path: String): ParcelFileDescriptor {
        val file = File(path)
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    override fun readText(path: String): String = File(path).readText()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun destroy() {
        System.exit(0)
    }
}
