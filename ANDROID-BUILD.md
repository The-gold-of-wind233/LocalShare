# 安卓端编译指南（新手版）

先说结论：**不能像电脑端那样双击 `build.bat` 就完事**，安卓端需要先装一个比较大的开发环境。

---

## 一、你真的需要安卓 App 吗？

**先别急着装环境**，看看这个对比：

| | 网页方案（已能用） | 安卓 App |
| --- | --- | --- |
| 装什么 | **什么都不用装** | 要装 Android Studio（约 6~10GB） |
| 花多久 | 0 分钟 | 首次约 1~2 小时 |
| 电脑 → 手机 | 打开网页就能收 | 全自动，后台也行 |
| 手机 → 电脑 | **要手动输入后点推送** | 全自动，复制即同步 |
| 适合 | 偶尔传一次、iPhone | 天天用、要求真·无感 |

**建议**：

- 如果你只是「偶尔把电脑上的东西传到手机」→ **用网页方案就够了，不用装安卓环境**
- 如果你想要「手机上复制，电脑上直接 Ctrl+V」这种真·无感 → 才需要装

---

## 二、如果确定要装，需要多大？

| 项目 | 大小 |
| --- | --- |
| Android Studio 安装包 | 约 1 GB |
| Android Studio 安装后 | 约 4 GB |
| Android SDK（Platform 34 + 构建工具） | 约 1~2 GB |
| Gradle 依赖缓存 | 约 0.5~1 GB |
| JDK 17（Android Studio 自带） | 约 0.3 GB |
| **合计** | **约 6~10 GB** |

另外：

- 硬盘要预留 **15GB 以上**（编译中间产物很占空间）
- 全程需要联网，首次下载较慢
- 内存建议 8GB 以上（4GB 会卡）

> 这是安卓开发的常态——不是这个项目特殊，所有安卓 App 都要这套环境。

---

## 三、详细步骤（照着做）

### 第 1 步：下载 Android Studio

1. 浏览器打开 `https://developer.android.com/studio`
2. 点「Download Android Studio」
3. 勾选同意协议，下载（约 1GB）
4. 双击安装，**一路 Next**，全部用默认选项

安装过程会自动带上 JDK 17，不用单独装 Java。

### 第 2 步：首次启动配置

1. 启动 Android Studio
2. 会弹出「Android Studio Setup Wizard」，选 **Standard**（标准）
3. 一路 Next，**它会自己下载 Android SDK**（这步最久，可能 10~30 分钟）
4. 看到「Finish」点它

> 如果中途提示要同意 licenses，全部点 **Accept**（同意）。

### 第 3 步：打开本项目

1. 在 Android Studio 里点 **File → Open**
2. 选择 `LocalShare-Android` 这个**文件夹**
   - 注意：是包含 `settings.gradle.kts` 的那一层
   - 不要选到更里面的 `app` 文件夹
3. 点 OK

### 第 4 步：等待 Gradle Sync

打开后右下角会转圈，显示「Gradle Sync in progress」。

- **第一次会很慢**（10~20 分钟），它在下载 Gradle 和依赖库
- 左下角 Build 窗口能看到进度
- 等它变成「Gradle sync finished」再继续

> 如果弹出「Android Gradle Plugin 需要更新」之类的提示，
> 一般可以直接点「Update」或「Use default」，按它说的做就行。

### 第 5 步：编译 APK

1. 顶部菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. 等待编译（首次 3~10 分钟）
3. 完成后右下角弹通知，点 **locate**（定位）链接
4. 会打开文件夹，里面有 `app-debug.apk`

### 第 6 步：装到手机

把 `app-debug.apk` 传到手机（微信/QQ/数据线都行），点开安装。

手机会提示「未知来源应用」，允许一次即可。

---

## 四、关于 Shizuku（授权用）

App 装好后，第一次用需要授权（让 App 能读手机剪贴板）。

推荐用 Shizuku，这是最稳的方式：

1. 手机上安装 Shizuku（Google Play 或 GitHub 搜 "Shizuku"）
2. 按 Shizuku 里的引导激活它（Android 11+ 用无线调试，不用连电脑）
3. 回到 LocalShare App，点「Shizuku 授权」
4. 允许一次即可

**没有 Shizuku 也能用**：App 里可以改用「无障碍服务」授权，
但国产手机（小米/华为/OPPO 等）可能会把它杀掉导致同步中断。

---

## 五、常见问题

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| 双击 `build.bat` 报「gradlew.bat 不是内部或外部命令」 | 还没用 Android Studio 打开过 | 按第 3 步先用 Android Studio 打开一次 |
| Gradle Sync 一直转圈 | 网络不通或很慢 | 换个网络，或挂代理；等多一会儿 |
| 提示 SDK licenses 未同意 | — | 按提示执行 `sdkmanager --licenses`，全部输入 y |
| 编译报「SDK not found」 | SDK 没装好 | File → Settings → Android SDK，勾选 Android 14 (API 34) 安装 |
| 手机装不上 APK | 未允许未知来源 | 手机设置里允许「安装未知应用」 |
| App 找不到电脑 | 防火墙/不同 Wi-Fi | 检查电脑防火墙放行，确认同一 Wi-Fi |

---

## 六、一个省事的替代方案

如果你不想装 6~10GB 的环境，但又想要「手机 → 电脑」自动同步，
可以考虑这个折中：

- **电脑 → 手机**：用网页方案（打开网页就能收）
- **手机 → 电脑**：在网页输入框里粘贴后点「推送到电脑」

比全自动多一步手动粘贴，但**完全不用装安卓环境**。

等哪天真的觉得这一步很烦，再回来装 Android Studio 也不迟。

---

## 七、总结

```
要做安卓 App：
  装 Android Studio（1小时，6~10GB）
  → 打开 LocalShare-Android 文件夹
  → 等 Gradle Sync
  → Build APK
  → 装到手机
  → 装 Shizuku 并授权
  → 开始用

不做安卓 App：
  电脑端 exe 已经能用
  → 手机扫码打开网页即可
  → 只是「手机→电脑」要手动粘贴一下
```
