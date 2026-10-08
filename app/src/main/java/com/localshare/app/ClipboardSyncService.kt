package com.localshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻同步服务（前台服务）。
 *
 * 双向全自动：
 *   电脑 → 手机：SSE 长连接，电脑剪贴板一变就推过来，收到后写入手机剪贴板。
 *                Android 对「写剪贴板」没有任何限制，因此这一路 100% 可用。
 *   手机 → 电脑：监听手机剪贴板变化，POST 给电脑。
 *                Android 10+ 限制后台读剪贴板，因此额外依赖无障碍服务
 *                （见 ClipboardAccessibilityService）作为可靠的触发源。
 */
enum class WorkMode { APP_OPS, ACCESSIBILITY, NONE }

class ClipboardSyncService : Service() {

    companion object {
        private const val CH_ID = "localshare_sync"
        private const val NOTIF_ID = 1001

        /** 当前存活实例，供无障碍服务调用 */
        var instance: ClipboardSyncService? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, ClipboardSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClipboardSyncService::class.java))
        }

        /**
         * 当前「手机 → 电脑」方向的工作模式。
         *
         * APP_OPS：已通过 Shizuku 授予 READ_CLIPBOARD，直接用系统监听，最稳定、不会被杀。
         * ACCESSIBILITY：回退到无障碍服务，ROM 可能回收它导致同步中断。
         * NONE：两者都没有，手机复制不会同步到电脑。
         */
        fun workMode(ctx: Context): WorkMode {
            if (ShizukuGrant.opGranted(ctx)) return WorkMode.APP_OPS
            if (isAccessibilityEnabled(ctx)) return WorkMode.ACCESSIBILITY
            return WorkMode.NONE
        }

        fun isAccessibilityEnabled(ctx: Context): Boolean {
            val expected = "${ctx.packageName}/${ClipboardAccessibilityService::class.java.name}"
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    private var clipboard: ClipboardManager? = null
    private var sseThread: Thread? = null

    /** 电脑推过来的最后内容：用于避免把它回推给电脑 */
    @Volatile private var lastRemoteText: String? = null

    /** 本机已推送过的内容：避免重复推送 */
    @Volatile private var lastLocalSent: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onLocalClipboardMaybeChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.configured(this)) {
            updateNotification("未配置电脑地址")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running.compareAndSet(false, true)) {
            return START_STICKY // 已在运行
        }

        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)

        startSseLoop()
        updateNotification(statusText())

        return START_STICKY // 被系统杀掉后自动重启
    }

    // ==================================================================
    //  电脑 → 手机（SSE 接收）
    // ==================================================================

    private fun startSseLoop() {
        sseThread = Thread({
            var delay = 1000L
            while (running.get()) {
                val url = Prefs.baseUrl(this)
                val q = Prefs.codeQuery(this)
                Api.stream(url, q, stop = { !running.get() }) { text ->
                    delay = 1000L          // 成功收到数据，重置退避
                    handleRemoteText(text)
                }

                if (!running.get()) break

                // 断线重连：指数退避，最长 15 秒
                updateNotification("连接中断，正在重试…")
                Thread.sleep(delay)
                delay = (delay * 2).coerceAtMost(15_000L)
            }
        }, "ls-sse").apply { isDaemon = true }
        sseThread?.start()
    }

    private fun handleRemoteText(text: String) {
        // 与本地剪贴板相同则无需写入（这通常是我们自己推过去的回环）
        val current = readClipboard()
        if (current != null && current == text) return

        lastRemoteText = text
        mainHandler.post {
            try {
                clipboard?.setPrimaryClip(ClipData.newPlainText("LocalShare", text))
                onSyncHappened("已接收电脑剪贴板", text)
            } catch (e: Exception) {
                // 个别 ROM 在极端情况下会抛异常，忽略即可
            }
        }
    }

    // ==================================================================
    //  手机 → 电脑（读取并推送）
    // ==================================================================

    /**
     * 剪贴板可能发生变化。
     * 触发源有两个：系统剪贴板监听回调、无障碍服务的界面变化事件。
     */
    fun onLocalClipboardMaybeChanged() {
        val text = readClipboard() ?: return
        if (text.isBlank()) return
        if (text == lastRemoteText) return   // 电脑刚推来的，不回推
        if (text == lastLocalSent) return    // 已经推过，不重复

        lastLocalSent = text
        executor.execute {
            val ok = Api.push(Prefs.baseUrl(this), Prefs.codeQuery(this), text)
            if (ok) onSyncHappened("已同步到电脑")
        }
    }

    private fun readClipboard(): String? {
        // ClipboardManager 读写需要在主线程
        if (Looper.myLooper() == Looper.getMainLooper()) return readClipboardOnMain()

        var result: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            result = readClipboardOnMain()
            latch.countDown()
        }
        return try {
            latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            result
        } catch (e: InterruptedException) {
            null
        }
    }

    private fun readClipboardOnMain(): String? = try {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        clip.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (e: Exception) {
        null
    }

    private fun statusText(): String {
        val target = "${Prefs.ip(this)}:${Prefs.port(this)}"
        return when (workMode(this)) {
            WorkMode.APP_OPS -> "同步中 · $target · AppOps 模式"
            WorkMode.ACCESSIBILITY -> "同步中 · $target · 无障碍模式"
            WorkMode.NONE -> "同步中 · $target · 仅接收（手机复制不会同步）"
        }
    }

    private fun onSyncHappened(msg: String, text: String? = null) {
        mainHandler.post {
            updateNotification(statusText())
            val i = Intent("com.localshare.app.SYNC_EVENT").apply {
                `package` = packageName
                putExtra("msg", msg)
                putExtra("time", System.currentTimeMillis())
                if (text != null) putExtra("text", text)
            }
            sendBroadcast(i)
        }
    }

    // ==================================================================
    //  通知 / 生命周期
    // ==================================================================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) != null) return
        val ch = NotificationChannel(CH_ID, "剪贴板同步", NotificationManager.IMPORTANCE_LOW).apply {
            description = "LocalShare 正在与电脑同步剪贴板"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("正在启动…")
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("LocalShare 剪贴板同步")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            nm?.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) { /* 忽略 */ }
    }

    override fun onDestroy() {
        running.set(false)
        try { clipboard?.removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        sseThread?.interrupt()
        executor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
