package com.localshare.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 剪贴板读取的触发源。
 *
 * ── 为什么必须有无障碍服务 ──────────────────────────────
 * Android 10 起，系统规定：只有「当前获得输入焦点的应用」或「默认输入法」
 * 才能读取剪贴板。后台应用、前台服务（Foreground Service）都不具备该权限，
 * 系统 ClipboardManager 的变更回调在后台也会停止触发。
 *
 * 无障碍服务是系统保留的豁免通道之一，它仍能读取剪贴板。
 * 因此这里不读取任何屏幕文字（canRetrieveWindowContent=false），
 * 只把「界面/文本发生变化」当作一个**信号**，然后去读系统剪贴板。
 *
 * 本服务不联网、不上传屏幕内容，只把剪贴板文本交给本进程的同步服务。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        /** 节流：界面事件非常密集，300ms 内只检查一次 */
        private const val MIN_INTERVAL = 300L
    }

    @Volatile
    private var lastCheck = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return

        // 只在「内容/焦点/窗口」变化时检查 —— 这些都是可能发生复制操作的时机
        when (type) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> scheduleCheck()
        }
    }

    private fun scheduleCheck() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCheck < MIN_INTERVAL) return
        lastCheck = now

        // 同进程直接调用，无需广播（Android 8+ 对隐式广播有限制）
        ClipboardSyncService.instance?.onLocalClipboardMaybeChanged()
    }

    override fun onInterrupt() {
        // 系统要求实现，无需处理
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务被系统绑定（用户刚开启）时立即检查一次
        ClipboardSyncService.instance?.onLocalClipboardMaybeChanged()
    }
}
