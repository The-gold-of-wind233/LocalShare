package com.localshare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后自动恢复同步。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Prefs.enabled(ctx) && Prefs.configured(ctx)) {
                    ClipboardSyncService.start(ctx)
                }
            }
        }
    }
}
