package com.localshare.app

import android.content.Context

/** 极简配置存储：电脑 IP、端口、访问口令、同步开关。 */
object Prefs {
    private const val NAME = "localshare"
    private const val K_IP = "ip"
    private const val K_PORT = "port"
    private const val K_CODE = "code"
    private const val K_ENABLED = "enabled"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun save(ctx: Context, ip: String, port: Int, code: String) {
        sp(ctx).edit()
            .putString(K_IP, ip.trim())
            .putInt(K_PORT, port)
            .putString(K_CODE, code.trim())
            .apply()
    }

    fun ip(ctx: Context): String = sp(ctx).getString(K_IP, "") ?: ""
    fun port(ctx: Context): Int = sp(ctx).getInt(K_PORT, 8080)
    fun code(ctx: Context): String = sp(ctx).getString(K_CODE, "") ?: ""

    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ENABLED, v).apply()
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, false)

    fun configured(ctx: Context): Boolean {
        val i = ip(ctx)
        val p = port(ctx)
        return i.isNotBlank() && p in 1..65535
    }

    /** 访问地址，例如 http://192.168.1.9:8080/ */
    fun baseUrl(ctx: Context): String = "http://${ip(ctx)}:${port(ctx)}/"

    /** 带口令的查询串（电脑端设置了口令时才需要） */
    fun codeQuery(ctx: Context): String {
        val c = code(ctx)
        return if (c.isBlank()) "" else "?code=${java.net.URLEncoder.encode(c, "UTF-8")}"
    }
}
