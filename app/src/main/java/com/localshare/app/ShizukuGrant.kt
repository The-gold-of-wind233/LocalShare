package com.localshare.app

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import rikka.shizuku.Shizuku

/**
 * 用 Shizuku 给自己授予后台读取剪贴板的权限。
 *
 * ── 为什么这是比无障碍更好的方案 ──────────────────────
 * Android 10+ 禁止后台读剪贴板，无障碍服务虽然能绕过，但它是一个
 * 「会被系统杀掉」的普通应用组件 —— 小米/华为等 ROM 会在几分钟后回收它，
 * 同步就静默失效了，用户还得手动重新开启。
 *
 * 而 AppOps 授权是**写进系统配置**的（/data/system/appops.xml）：
 *   · 一次授权，永久有效（重启后仍在）
 *   · 不涉及任何常驻组件，不存在「被杀后台」这回事
 *   · 授予后 App 直接用标准 ClipboardManager 监听，行为与普通前台应用一致
 *
 * Shizuku 只在**授权那一刻**被用到，之后完全不需要它运行。
 *
 * ── 权限最小化 ────────────────────────────────────────
 * 只授予 READ_CLIPBOARD 这一项。不申请 SYSTEM_ALERT_WINDOW、不申请 READ_LOGS，
 * 这些都是同类工具为了变通而常开的权限，本应用不需要。
 */
object ShizukuGrant {

    /** AppOps 操作名。注意是 READ_CLIPBOARD，写成 CLIPBOARD_READ 会报 Unknown operation string */
    private const val OP_READ_CLIPBOARD = "READ_CLIPBOARD"
    /**
     * AppOps 检查用的 op 字符串。
     * 不能用 AppOpsManager.OPSTR_READ_CLIPBOARD —— 该常量在公开 SDK 里不可见（@hide），
     * 编译会报 Unresolved reference，因此这里直接写死其值。
     */
    private const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"

    private const val REQ_CODE = 9001

    // ==================================================================
    //  状态查询
    // ==================================================================

    /** 是否已经拥有后台读取剪贴板的权限。Android 10 以下无此限制，直接返回 true。 */
    fun opGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            appOps.unsafeCheckOpNoThrow(
                OPSTR_READ_CLIPBOARD,
                Process.myUid(),
                ctx.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** Shizuku 服务是否正在运行且已对本应用授权。 */
    fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** Shizuku 的运行身份：0 = root，2000 = ADB（无线调试） */
    fun shizukuUid(): Int = try {
        if (shizukuReady()) Shizuku.getUid() else -1
    } catch (e: Exception) {
        -1
    }

    private const val PERMISSION_GRANTED = android.content.pm.PackageManager.PERMISSION_GRANTED

    // ==================================================================
    //  授权流程
    // ==================================================================

    /**
     * 确保拥有后台读剪贴板权限。
     *
     * @param onDone 参数一为是否成功；参数二为用户可读的说明（失败时用于提示）
     */
    fun ensure(
        activity: Activity,
        onDone: (Boolean, String) -> Unit
    ) {
        if (opGranted(activity)) {
            onDone(true, "已具备后台读剪贴板权限")
            return
        }

        // Shizuku 未运行
        if (!ping()) {
            onDone(false, "Shizuku 未运行。请先安装并启动 Shizuku（推荐使用无线调试方式激活），再回来点击授权。")
            return
        }

        // Shizuku 已运行但未对本应用授权 → 先请求授权
        if (!shizukuReady()) {
            requestPermission(activity) { granted ->
                if (!granted) {
                    onDone(false, "Shizuku 拒绝了本应用的请求，请在 Shizuku 中允许后再试。")
                } else {
                    runGrant(activity, onDone)
                }
            }
            return
        }

        runGrant(activity, onDone)
    }

    private fun ping(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    private fun requestPermission(activity: Activity, cb: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQ_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                cb(grantResult == PERMISSION_GRANTED)
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQ_CODE)
        } catch (e: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            cb(false)
        }
    }

    // ==================================================================
    //  绑定 UserService 并执行命令
    // ==================================================================

    private fun runGrant(activity: Activity, onDone: (Boolean, String) -> Unit) {
        val pkg = activity.packageName

        val args = Shizuku.UserServiceArgs(
            ComponentName(pkg, ShellService::class.java.name)
        )
            .daemon(false)                 // 用完即弃，不常驻
            .processNameSuffix("shell")
            .version(1)
            .tag("localshare-shell")

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val shell = IShellService.Stub.asInterface(service)
                try {
                    // 1) 授权。appops 写入 /data/system/appops.xml，重启后依然有效
                    // 兼容：部分 ROM 的 cmd appops 不存在，退回 appops
                    var out = shell.exec("cmd appops set $pkg $OP_READ_CLIPBOARD allow")
                    if (out.startsWith("-1") || out.contains("Unknown command", true)) {
                        out = shell.exec("appops set $pkg $OP_READ_CLIPBOARD allow")
                    }

                    // 2) 立即回读确认，避免"执行了但没生效"
                    val verify = shell.exec("cmd appops get $pkg $OP_READ_CLIPBOARD")
                    val ok = opGranted(activity) || verify.contains("allow", true)

                    onDone(ok, if (ok) {
                        "授权成功。今后不需要 Shizuku 运行，也不需要无障碍服务。"
                    } else {
                        "命令已执行但校验未通过：${out.trim()} / ${verify.trim()}"
                    })
                } catch (e: RemoteException) {
                    onDone(false, "调用特权服务失败：${e.message}")
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this, true)
                    } catch (e: Exception) {
                        // 解绑失败不影响结果
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // 特权进程退出，无需处理
            }

            override fun onBindingDied(name: ComponentName) {
                onDone(false, "特权服务连接中断，请重试")
            }

            override fun onNullBinding(name: ComponentName) {
                onDone(false, "特权服务返回空绑定，请重启 Shizuku 后重试")
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            onDone(false, "绑定 Shizuku 服务失败：${e.message}")
        }
    }

    /** 主动撤销授权（卸载前或想改用无障碍时使用）。 */
    fun revoke(activity: Activity, onDone: (Boolean, String) -> Unit) {
        if (!shizukuReady()) {
            onDone(false, "Shizuku 不可用")
            return
        }
        val pkg = activity.packageName
        val args = Shizuku.UserServiceArgs(
            ComponentName(pkg, ShellService::class.java.name)
        ).daemon(false).processNameSuffix("shell").version(1).tag("localshare-shell")

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    IShellService.Stub.asInterface(service)
                        .exec("cmd appops set $pkg $OP_READ_CLIPBOARD default")
                    onDone(true, "已撤销后台读剪贴板权限")
                } catch (e: Exception) {
                    onDone(false, "撤销失败：${e.message}")
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this, true)
                    } catch (e: Exception) { /* 忽略 */ }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            onDone(false, "绑定失败：${e.message}")
        }
    }
}
