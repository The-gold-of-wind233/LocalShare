# LocalShare Android 端 —— 真正的双向无感剪贴板同步

配套的手机端 App。**电脑端工程见 `../LocalShare`。**

> 网页方案（电脑端自带的 `/` 页面）在「电脑 → 手机」这一路已经是无感的，
> 但「手机 → 电脑」必须手动点按钮 —— 因为网页拿不到手机的系统剪贴板。
> 这个 App 就是为了解决这一路：装了它之后，**两边复制都不需要任何手动操作**。

**两种授权模式，默认推荐 Shizuku**：

| 模式 | 授权方式 | 是否会被杀后台 | 推荐度 |
| --- | --- | --- | --- |
| **Shizuku（AppOps）** | 用 Shizuku 授予 `READ_CLIPBOARD` AppOps，写入系统配置 | **不会**，无任何常驻组件 | ★★★ 推荐 |
| 无障碍服务 | 系统设置里开启无障碍 | **会**，国产 ROM 常在几分钟后回收 | ★ 备选 |

Shizuku 只在授权那一刻用到，之后**完全不需要它运行** —— 授权结果写在系统配置里，重启后依然有效。

---

## 一、同步原理

```
电脑 → 手机（这条路 100% 可靠）
  电脑 Ctrl+C
    ↓ WM_CLIPBOARDUPDATE，电脑端完全无感
  ClipboardStore（内存，不落盘）
    ↓ SSE 长连接 /api/clip/stream，毫秒级推送
  手机 App
    ↓ setPrimaryClip
  手机剪贴板，直接粘贴即可

手机 → 电脑（这条路依赖无障碍服务）
  手机任意 App 里复制
    ↓ 无障碍服务收到「界面/文本变化」事件（300ms 节流）
  手机 App 读取系统剪贴板
    ↓ POST /api/clipboard
  电脑端写入系统剪贴板 + 弹 Toast
    ↓
  电脑直接 Ctrl+V
```

### 「手机 → 电脑」的两条授权路径

```
[推荐] Shizuku 路径
  Shizuku（shell 权限）
    ↓ 一次性执行
  cmd appops set com.localshare.app READ_CLIPBOARD allow
    ↓ 写入 /data/system/appops.xml（永久有效）
  App 直接 addPrimaryClipChangedListener 后台读剪贴板
    ↓
  无额外常驻组件，不存在被杀后台的问题

[备选] 无障碍路径（Shizuku 不可用时的兜底）
  无障碍服务监听界面变化事件
    ↓
  去读系统剪贴板并推送
```

**不再有轮询**：电脑端新增了 SSE 端点 `/api/clip/stream`（20 秒心跳保活），
手机端保持一条长连接，内容一变立刻推过来。手机端断线后按指数退避（1s→2s→…→15s）自动重连。

**回环抑制**：电脑推给手机的内容会被手机记录为 `lastRemoteText`，
系统剪贴板回调发现内容等于它就跳过 —— 不会把同一段文本无限来回推。

---

## 二、编译

> **不想装 Android Studio？看 `CLOUD-BUILD.md`** —— 用 GitHub Actions 免费云编译，
> 本地零安装，上传代码后自动出 APK。
>
> **新手想用 Android Studio？看 `ANDROID-BUILD.md`**（图文步骤 + 环境体积说明 + 常见问题）。
> 本节是给有安卓开发经验的人看的速查版。
>
> ⚠️ 本项目**没有**随包附带 `gradlew.bat` / `gradle-wrapper.jar`，
> 所以**不能直接双击 `build.bat` 编译**。
> 必须先用 Android Studio 打开一次，它会自动生成 Gradle 启动脚本。

### 环境体积（首次约 6~10 GB）

| 项目 | 大小 |
| --- | --- |
| Android Studio（含自带 JDK 17） | 约 4 GB |
| Android SDK Platform 34 + Build Tools | 约 1~2 GB |
| Gradle 与依赖缓存 | 约 0.5~1 GB |

如果只是为了偶尔传文件，建议直接用**电脑端内置网页方案**，不必装这套环境。

### 环境

- Android Studio **Giraffe 或更高**（AGP 8.1.4 / Gradle 8.0 / Kotlin 1.9.22）
- `compileSdk 34`、`minSdk 26`（Android 8.0+）、`targetSdk 34`
- 依赖极少：只用 `androidx.core:core-ktx:1.12.0` + `dev.rikka.shizuku:api/provider:13.1.5`
  （Shizuku 托管在 `https://maven.rikka.app/`，已配置在 `settings.gradle.kts`）
- 无第三方网络库，HTTP 用 `HttpURLConnection`；SSE 也自己解析

### 命令行（仅限已用 Android Studio 打开过）

```bash
cd LocalShare-Android
./gradlew assembleDebug      # 或 Windows: gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

未用 Android Studio 打开过时，会提示 `gradlew.bat 不是内部或外部命令`。

### Android Studio

File → Open → 选中 `LocalShare-Android` 目录 → 等待 Gradle 同步 → Run。

> 首次构建需要联网下载 Gradle 与 Android Gradle Plugin。

---

## 三、使用（几步一次性设置）

1. 电脑上打开 LocalShare，确认服务已启动
2. 手机装好 App 打开 → 点 **「查找电脑」**（UDP 广播自动发现）→ 点列表里的那台电脑
   - 找不到就手动填 IP / 端口 / 口令，点「测试连接」
3. 授权「手机 → 电脑」方向，**二选一**：
   - **推荐**：点 **「去授权」**（Shizuku）。需先安装 [Shizuku](https://shizuku.rikka.app/) 并按其指引激活（Android 11+ 可用无线调试，无需连电脑）
   - **备选**：点 **「去开启」** 打开无障碍服务（没有 Shizuku 时的方案，可能被系统回收）
4. 点 **「去设置」** 关闭电池优化，然后点 **「开始同步」**

之后就什么都不用管了：

- 电脑上复制 → 手机直接粘贴
- 手机上复制 → 电脑上直接 `Ctrl+V`

顶部会有一条常驻通知（前台服务必需）。Android 13+ 需要授权通知权限，否则通知不显示（服务仍会运行）。

---

## 四、授权方式：Shizuku（推荐）vs 无障碍

| 项目 | 原因 |
| --- | --- |
| **Shizuku 授权（推荐）** | Android 10 起，只有「当前有输入焦点的应用」或「默认输入法」能读剪贴板，**后台应用和前台服务都不行**。授予 `READ_CLIPBOARD` AppOps 后即可后台读取，且该授权写进系统配置、永久有效、不依赖任何常驻组件 |
| **无障碍服务（备选）** | 系统保留的豁免通道之一。但它本身是个普通应用组件，**会被国产 ROM 回收**，导致同步静默失效 |
| **关闭电池优化** | 否则 Doze / ROM 省电策略会在几分钟后杀掉前台服务（这一项即使走 Shizuku 路径也建议做，因为 SSE 接收仍依赖前台服务） |
| **通知权限** | 前台服务必须挂一条通知，Android 13+ 需要 `POST_NOTIFICATIONS` 才会显示 |

### Shizuku 路径的权限边界（最小权限）

只申请 **READ_CLIPBOARD 一项** AppOps。同类工具为了变通常开的 `SYSTEM_ALERT_WINDOW`（悬浮窗）、`READ_LOGS`（读系统日志）这里**一概不用**。
Shizuku 仅在点击「去授权」的那一刻被使用，用完后 UserService 进程即退出（`daemon(false)`）。

### 无障碍路径的隐私边界

代码里 `canRetrieveWindowContent = false`，也就是**不读取任何屏幕文字**。
无障碍服务只被当作「界面发生了变化」的**信号**，收到信号后去读的是系统剪贴板本身。
服务不联网、不上传屏幕内容，剪贴板文本只发给局域网内的电脑。

---

## 五、已知边界（重要，请先看）

| 情况 | 表现 | 说明 |
| --- | --- | --- |
| **Android 12+ 读取剪贴板** | 走 AppOps 路径时**不再弹** toast；走无障碍路径可能弹 | AppOps 授权后属于系统认可的读取方，不提示 |
| **Shizuku 未激活** | 「去授权」提示 Shizuku 未运行 | ADB 模式的 Shizuku 每次重启后需重新激活（Android 11+ 可用无线调试快速激活）；但**授权一旦完成就永久有效**，之后 Shizuku 停了也不影响 |
| **重装 App** | AppOps 授权可能失效（uid 变化） | 重新点一次「去授权」即可 |
| **部分国产 ROM（小米/华为/OPPO 等）** | 走无障碍路径时偶尔掉事件；走 AppOps 路径不受影响 | 这正是改用 Shizuku 的主要收益。仍建议在该 ROM 的「自启动 / 省电策略」中给本 App 放行 |
| **自绘菜单的应用（微信、部分浏览器）** | 长按复制走的是 App 自己的菜单，系统无障碍事件可能收不到 | 多数情况下窗口变化事件仍会触发，但不保证；可在这些 App 里复制后切一下界面 |
| **非文本内容** | 图片、文件不走剪贴板同步 | 传文件请用电脑端的「文件分享」Tab |
| **跨网段 / 公网** | 广播发现不可用 | 手动填 IP，且务必设置访问口令 |

如果某次同步没生效，打开 App 看「同步测试」区：在电脑上复制一段文本，这里应立刻显示。
不显示就是 SSE 连接断了（检查电脑端是否运行、Wi-Fi 是否相同）；显示了但手机粘贴不是它，就是无障碍没拿到事件。

---

## 六、项目结构

```
LocalShare-Android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/src/main/
    ├── AndroidManifest.xml                  # 权限、前台服务(dataSync)、无障碍服务、开机自启
    ├── aidl/com/localshare/app/IShellService.aidl
    ├── java/com/localshare/app/
    │   ├── MainActivity.kt                  # 发现电脑、授权引导、开关同步、测试区
│   ├── ShizukuGrant.kt                  # Shizuku 授权：AppOps 状态检查 + UserService 绑定
│   ├── ShellService.kt                  # 运行在 shell 进程内的命令执行服务
│   ├── IShellService.aidl               # (aidl/) UserService 的 AIDL 接口
    │   ├── ClipboardSyncService.kt          # 前台服务：SSE 接收 + 剪贴板监听 + POST 推送
    │   ├── ClipboardAccessibilityService.kt # 读剪贴板的触发源（不读屏幕文字）
    │   ├── BootReceiver.kt                  # 开机 / 应用更新后恢复同步
    │   ├── Discovery.kt                     # UDP 广播发现（端口 41523）
    │   ├── Api.kt                           # ping / push / SSE stream
    │   └── Prefs.kt                         # IP、端口、口令、开关
    └── res/
        ├── layout/activity_main.xml
        ├── xml/accessibility_service_config.xml
        └── values/strings.xml, themes.xml
```

### 电脑端为此新增的部分

- `/api/clip/stream`（SSE）、`/api/ping`：`Services/HttpFileServer.cs`
- UDP 发现服务（端口 41523）：`Services/DiscoveryService.cs`
- 长连接保活：`TimeoutManager.IdleConnection = 30 分钟`

---

## 七、卸载

系统设置 → 应用 → LocalShare → 卸载。

**卸载前建议先处理这两处授权**：

1. 系统「无障碍」设置里关掉 LocalShare 的开关（否则残留在已启用列表里，重装后可能无法再次开启）
2. 如果用过 Shizuku 授权，可在 App 里再次点「去授权」位置撤销，或在 Shizuku 可用的情况下执行：
   `adb shell cmd appops set com.localshare.app READ_CLIPBOARD default`
