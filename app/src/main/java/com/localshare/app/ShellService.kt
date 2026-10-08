package com.localshare.app

import android.os.RemoteException
import kotlin.system.exitProcess

/**
 * Shizuku 的 User Service：运行在 shell / root 进程中。
 *
 * 官方已弃用 Shizuku.newProcess（"Prepare to remove Shizuku#newProcess,
 * developers should have to use UserService instead"），因此这里用 UserService
 * 在特权进程里执行 appops 命令。
 *
 * 注意：UserService 所在进程不是正常的 Android 应用进程，Context 的很多能力
 * （registerReceiver / getContentResolver 等）不可用，但 Runtime.exec 可以正常工作。
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        // Shizuku 不会自动杀掉 UserService 进程，必须自己退出
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText() +
                    process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            "$code\n$output"
        } catch (e: Exception) {
            "-1\n${e.message ?: "exec failed"}"
        }
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        // 兜底：确保 destroy 事务一定能终止进程
        if (code == 16777114) {
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}
