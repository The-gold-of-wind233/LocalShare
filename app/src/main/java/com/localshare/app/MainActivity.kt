package com.localshare.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 主界面。职责只有四个：
 *   1. 找到电脑（UDP 广播自动发现，失败可手动填 IP）
 *   2. 引导完成三个无感前提（无障碍 / 电池 / 通知）
 *   3. 开关同步服务
 *   4. 显示同步状态，方便排查
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_FILE = 2001
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private lateinit var tvState: TextView
    private lateinit var tvEndpoint: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvPcEmpty: TextView
    private lateinit var pcList: LinearLayout
    private lateinit var btnSyncToggle: Button
    private lateinit var btnDiscover: Button
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etCode: EditText
    private lateinit var tvReceived: TextView
    private lateinit var etSend: EditText
    private lateinit var tvUpProgress: TextView
    private lateinit var recvList: LinearLayout

    private val syncEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("msg") ?: return
            val text = intent.getStringExtra("text")
            val t = timeFmt.format(Date())
            tvLastSync.text = "$t  $msg"
            if (!text.isNullOrEmpty()) {
                tvReceived.text = text
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupButtons()
        fillManualForm()

        // 监听同步事件（服务与本 Activity 同进程）
        val filter = IntentFilter("com.localshare.app.SYNC_EVENT")
        ContextCompat.registerReceiver(
            this, syncEventReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK) return

        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data?.data?.let { uris.add(it) }

        if (uris.isEmpty()) { toast("没有选中文件"); return }
        doUpload(uris)
    }

    /** 把选中的文件依次上传到电脑。content:// URI 需先拷到缓存目录才能拿到 File。 */
    private fun doUpload(uris: List<Uri>) {
        if (!Prefs.configured(this)) { toast("请先配置电脑地址"); return }
        val btn = findViewById<Button>(R.id.btnPickFile)
        btn.isEnabled = false

        io.execute {
            var ok = 0
            uris.forEachIndexed { idx, uri ->
                val (name, file) = copyToCache(uri) ?: return@forEachIndexed
                main.post {
                    tvUpProgress.text = "(${idx + 1}/${uris.size}) 正在上传 $name"
                }
                val done = Api.upload(
                    Prefs.baseUrl(this), Prefs.codeQuery(this), name,
                    contentResolver.getType(uri) ?: "", file
                ) { sent ->
                    // 进度按 10% 粒度刷新，避免频繁切主线程
                    if (sent % (512 * 1024) < 64 * 1024) {
                        main.post {
                            tvUpProgress.text = "(${idx + 1}/${uris.size}) $name — ${sent / 1024} KB"
                        }
                    }
                }
                file.delete()   // 缓存用完即删
                if (done) ok++
            }

            main.post {
                btn.isEnabled = true
                tvUpProgress.text = if (ok == uris.size) "上传完成（$ok 个）"
                else "完成 $ok/${uris.size} 个，部分失败"
                toast(if (ok > 0) "已上传 $ok 个文件" else "上传失败，请检查连接")
                loadReceived()
            }
        }
    }

    /** 把 content:// URI 拷到缓存目录，返回 (显示名, 临时文件)。 */
    private fun copyToCache(uri: Uri): Pair<String, java.io.File>? {
        val name = queryName(uri) ?: "upload_${System.currentTimeMillis()}.bin"
        return try {
            val out = java.io.File(cacheDir, "up_${System.currentTimeMillis()}_${name.hashCode()}")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o -> input.copyTo(o, 64 * 1024) }
            } ?: return null
            name to out
        } catch (e: Exception) {
            null
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 列出电脑上已收到的文件。 */
    private fun loadReceived() {
        if (!Prefs.configured(this)) return
        io.execute {
            val list = Api.received(Prefs.baseUrl(this), Prefs.codeQuery(this))
            main.post {
                recvList.removeAllViews()
                if (list.isEmpty()) {
                    recvList.addView(TextView(this).apply {
                        text = "（暂无）"
                        setTextColor(0xFF8A93A6.toInt())
                        textSize = 13f
                        setPadding(8, 8, 8, 8)
                    })
                    return@post
                }
                list.forEach { f ->
                    recvList.addView(TextView(this).apply {
                        text = "${f.name}  ·  ${f.size / 1024} KB  ·  ${f.time}"
                        setTextColor(0xFF1B1F27.toInt())
                        textSize = 13f
                        setPadding(8, 10, 8, 10)
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        loadReceived()
    }

    override fun onDestroy() {
        try { unregisterReceiver(syncEventReceiver) } catch (e: Exception) { }
        io.shutdownNow()
        super.onDestroy()
    }

    // ==================================================================
    //  初始化
    // ==================================================================

    private fun bindViews() {
        tvState = findViewById(R.id.tvState)
        tvEndpoint = findViewById(R.id.tvEndpoint)
        tvLastSync = findViewById(R.id.tvLastSync)
        tvPcEmpty = findViewById(R.id.tvPcEmpty)
        pcList = findViewById(R.id.pcList)
        btnSyncToggle = findViewById(R.id.btnSyncToggle)
        btnDiscover = findViewById(R.id.btnDiscover)
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        etCode = findViewById(R.id.etCode)
        tvReceived = findViewById(R.id.tvReceived)
        etSend = findViewById(R.id.etSend)
        tvUpProgress = findViewById(R.id.tvUpProgress)
        recvList = findViewById(R.id.recvList)
    }

    private fun setupButtons() {
        btnDiscover.setOnClickListener { doDiscover() }
        btnSyncToggle.setOnClickListener { toggleSync() }

        findViewById<Button>(R.id.btnShizuku).setOnClickListener { doShizukuGrant() }

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                startActivityForResult(Intent.createChooser(i, "选择要上传的文件"), REQ_PICK_FILE)
            } catch (e: Exception) {
                toast("无法打开文件选择器")
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("找到 LocalShare → 打开开关，然后返回本页")
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBattery() }

        findViewById<Button>(R.id.btnNotify).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            } else {
                openNotificationSettings()
            }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }

        findViewById<Button>(R.id.btnPush).setOnClickListener {
            val text = etSend.text.toString()
            if (text.isBlank()) { toast("请输入内容"); return@setOnClickListener }
            io.execute {
                val ok = Api.push(Prefs.baseUrl(this), Prefs.codeQuery(this), text)
                main.post { toast(if (ok) "已推送到电脑" else "推送失败，请检查连接") }
            }
        }

        // 手动输入即保存
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { saveManual() }
        }
        etIp.addTextChangedListener(watcher)
        etPort.addTextChangedListener(watcher)
        etCode.addTextChangedListener(watcher)
    }

    private fun fillManualForm() {
        etIp.setText(Prefs.ip(this))
        etPort.setText(if (Prefs.port(this) > 0) Prefs.port(this).toString() else "8080")
        etCode.setText(Prefs.code(this))
    }

    private fun saveManual() {
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 8080
        val code = etCode.text.toString().trim()
        if (ip.isNotEmpty()) Prefs.save(this, ip, port, code)
    }

    // ==================================================================
    //  自动发现
    // ==================================================================

    private fun doDiscover() {
        btnDiscover.isEnabled = false
        btnDiscover.text = "扫描中…"
        tvPcEmpty.text = "正在扫描局域网…"

        io.execute {
            val list = try { Discovery.discover() } catch (e: Exception) { emptyList() }
            main.post {
                btnDiscover.isEnabled = true
                btnDiscover.text = "查找电脑"
                renderPcList(list)
            }
        }
    }

    private fun renderPcList(list: List<PcInfo>) {
        // 保留占位 TextView，其余动态项移除
        for (i in pcList.childCount - 1 downTo 0) {
            val child = pcList.getChildAt(i)
            if (child.id != R.id.tvPcEmpty) pcList.removeViewAt(i)
        }

        if (list.isEmpty()) {
            tvPcEmpty.text = "没找到电脑。\n请确认：电脑端 LocalShare 已运行、手机与电脑同一 Wi-Fi、路由器未开启 AP 隔离。"
            return
        }

        tvPcEmpty.text = "点击连接："

        list.forEach { pc ->
            val row = TextView(this).apply {
                text = pc.toString()
                textSize = 14f
                setTextColor(0xFF1B1F27.toInt())
                setPadding(8, 14, 8, 14)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener {
                    Prefs.save(this@MainActivity, pc.ip, pc.port, Prefs.code(this@MainActivity))
                    fillManualForm()
                    toast("已选择 ${pc.ip}:${pc.port}")
                    refreshStatus()
                    testConnection()
                }
            }
            pcList.addView(row)
        }
    }

    // ==================================================================
    //  同步开关
    // ==================================================================

    private fun toggleSync() {
        if (!Prefs.configured(this)) {
            toast("请先查找电脑或手动填写 IP")
            return
        }

        if (ClipboardSyncService.instance != null) {
            ClipboardSyncService.stop(this)
            Prefs.setEnabled(this, false)
            toast("已停止同步")
        } else {
            when (ClipboardSyncService.workMode(this)) {
                WorkMode.APP_OPS -> { /* 最佳模式，无需提示 */ }
                WorkMode.ACCESSIBILITY -> { /* 可用，但不如 AppOps 稳定 */ }
                WorkMode.NONE -> toast("建议先用 Shizuku 授权；否则需开启无障碍服务，且可能被系统杀后台")
            }
            Prefs.setEnabled(this, true)
            ClipboardSyncService.start(this)
            toast("同步已开启")
        }
        main.postDelayed({ refreshStatus() }, 400)
    }

    /**
     * 用 Shizuku 给自己授予后台读剪贴板权限。
     * 成功后写入系统 AppOps 配置，之后不需要 Shizuku 运行，也不需要无障碍服务。
     */
    private fun doShizukuGrant() {
        findViewById<Button>(R.id.btnShizuku).isEnabled = false
        toast(if (ShizukuGrant.opGranted(this)) "已授权，正在校验…" else "正在通过 Shizuku 授权…")

        ShizukuGrant.ensure(this) { ok, msg ->
            runOnUiThread {
                findViewById<Button>(R.id.btnShizuku).isEnabled = true
                toast(msg)
                refreshStatus()

                // 授权刚生效时重启一次同步服务，让新的权限立刻作用于当前进程
                if (ok && ClipboardSyncService.instance != null) {
                    ClipboardSyncService.stop(this)
                    main.postDelayed({ ClipboardSyncService.start(this); refreshStatus() }, 500)
                }
            }
        }
    }

    private fun testConnection() {
        if (!Prefs.configured(this)) { toast("请先填写 IP"); return }
        saveManual()

        val url = Prefs.baseUrl(this)
        io.execute {
            // 用带原因的版本：失败时把真实错误显示出来，而不是笼统一句话
            val (ok, reason) = Api.pingWithReason(url, Prefs.codeQuery(this))
            main.post {
                if (ok) {
                    toast("连接成功")
                    tvEndpoint.text = "电脑：${url.trimEnd('/')}"
                } else {
                    toast("连接失败：$reason")
                }
                refreshStatus()
            }
        }
    }

    // ==================================================================
    //  状态刷新
    // ==================================================================

    private fun refreshStatus() {
        val running = ClipboardSyncService.instance != null
        val configured = Prefs.configured(this)
        val mode = ClipboardSyncService.workMode(this)

        when {
            running -> {
                tvState.text = "同步中"
                tvState.setTextColor(0xFF22C55E.toInt())
                btnSyncToggle.text = "停止同步"
            }
            configured -> {
                tvState.text = "已配置，未启动"
                tvState.setTextColor(0xFFF59E0B.toInt())
                btnSyncToggle.text = "开始同步"
            }
            else -> {
                tvState.text = "未连接"
                tvState.setTextColor(0xFF8A93A6.toInt())
                btnSyncToggle.text = "开始同步"
            }
        }
        tvEndpoint.text = if (configured) {
            val suffix = when (mode) {
                WorkMode.APP_OPS -> " · AppOps 模式"
                WorkMode.ACCESSIBILITY -> " · 无障碍模式"
                WorkMode.NONE -> " · 仅接收（手机复制不同步）"
            }
            "电脑：${Prefs.baseUrl(this).trimEnd('/')}$suffix"
        } else "尚未发现电脑"

        val (shizukuOk, shizukuText) = when {
            ShizukuGrant.opGranted(this) -> true to "已授权 · AppOps 模式（推荐，不会被杀后台）"
            ShizukuGrant.shizukuReady() -> false to "Shizuku 已就绪，点击授权"
            else -> false to "未授权（需先安装并启动 Shizuku）"
        }
        setRequirement(R.id.tvShizukuState, shizukuOk, shizukuText, shizukuText)
        // 已用 AppOps 模式时，无障碍变成可选项
        findViewById<Button>(R.id.btnAccessibility).isEnabled = !shizukuOk

        setRequirement(R.id.tvAccessibilityState,
            ClipboardSyncService.isAccessibilityEnabled(this),
            "已开启",
            if (shizukuOk) "不需要（AppOps 模式已生效）" else "未开启（无 Shizuku 时必需，否则手机复制不同步）")

        setRequirement(R.id.tvBatteryState, isIgnoringBattery(),
            "已加入白名单", "未加入白名单（系统可能在后台杀掉同步）")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            setRequirement(R.id.tvNotifyState, granted, "已授权", "未授权（前台服务通知不会显示）")
        } else {
            setRequirement(R.id.tvNotifyState, true, "不需要（Android 13 以下）", "")
        }
    }

    private fun setRequirement(id: Int, ok: Boolean, okText: String, failText: String) {
        val tv = findViewById<TextView>(id)
        tv.text = if (ok) okText else failText
        tv.setTextColor(if (ok) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
    }

    // ==================================================================
    //  系统设置跳转
    // ==================================================================

    private fun isIgnoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (isIgnoringBattery()) { toast("已在白名单中"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                // 部分国产 ROM 不支持该 Intent，退回到电池设置页
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (e2: Exception) { toast("请在系统设置中手动关闭本应用的电池优化") }
            }
        }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
