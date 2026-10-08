# 不装环境编译安卓 APK（GitHub Actions 免费云编译）

**核心思路**：把代码传到 GitHub，用 GitHub 的免费服务器帮你编译，编译完下载 APK。
**你本地什么都不用装**，只要能上网就行。

预计耗时：**首次约 30 分钟**（主要是注册和上传），之后每次编译 **3~5 分钟**。

---

## 开始前：确认你是不是真的需要

| 你的情况 | 建议 |
| --- | --- |
| 只是偶尔把电脑的东西传到手机 | **不用折腾这个**，电脑端网页方案已经够用 |
| 想要「手机上复制 → 电脑上直接 Ctrl+V」 | 值得做，往下看 |

---

## 第 1 步：注册 GitHub（免费）

1. 浏览器打开 `https://github.com`
2. 点右上角 **Sign up**（注册）
3. 填邮箱、密码、用户名，按提示完成验证
4. 注册完成后登录

> 如果 GitHub 打不开或很慢，是网络问题。可以换个时间再试，
> 或者用手机流量开热点给电脑试试。

---

## 第 2 步：创建新仓库

1. 登录后，点右上角 **+** 号 → **New repository**
2. 按下面填写：

| 项目 | 填什么 |
| --- | --- |
| Repository name | `LocalShare`（随便起，英文） |
| 说明（Description） | 可以不填 |
| 选 **Public**（公开） | ⚠️ **必须选 Public**，私有仓库的 Actions 有额度限制 |
| 勾选 **Add a README file** | 可以不勾 |

3. 点绿色按钮 **Create repository**

创建后会跳转到一个页面，显示仓库地址。

---

## 第 3 步：上传代码（网页拖拽，不用装 git）

1. 在仓库页面，看到文件列表
2. 点中间那行蓝色的 **uploading an existing file**
   （如果没看到，就直接把文件拖到页面上）
3. 把 **`LocalShare-Android` 文件夹里的所有内容**拖进去

**要拖进去的东西**（全选，一起拖）：

```
build.gradle.kts
settings.gradle.kts
gradle.properties
build.bat
app/                    ← 整个文件夹
gradle/                 ← 整个文件夹
.github/                ← 整个文件夹（重要！里面是编译配置）
README.md（可选）
ANDROID-BUILD.md（可选）
```

> ⚠️ `.github` 文件夹**必须上传**，编译配置就在里面。
> 但它是隐藏文件夹（名字以点开头），Windows 资源管理器里可能看不见。
>
> **怎么显示隐藏文件夹**：
> 打开任意文件夹 → 顶部点「查看」→ 勾选「隐藏的项目」
>
> 如果实在拖不进去，看第 3 步的「备用方案」。

4. 拖完后，页面下方会出现文件列表
5. 点绿色按钮 **Commit changes**

---

### 第 3 步备用方案：用命令行上传（如果拖拽看不见 .github）

如果你能把 `.github` 拖上去，跳过这段。

否则，在 `LocalShare-Android` 文件夹里打开 PowerShell（在文件夹空白处按住 Shift + 右键 → 「在此处打开 PowerShell 窗口」），粘贴：

```
git init
git add -A
git commit -m "first"
git branch -M main
git remote add origin https://github.com/你的用户名/LocalShare.git
git push -u origin main
```

执行到 `git push` 时会弹窗让你登录 GitHub，按提示登录即可。

> 如果提示没有 git，需要先装 Git：https://git-scm.com/download/win

---

## 第 4 步：开始编译

1. 回到仓库页面，点顶部的 **Actions** 标签
2. 如果看到提示「Workflows 未启用」，点绿色按钮 **I understand my workflows, go ahead and enable them**
3. 左侧列表里点 **Build APK**
4. 右侧有个 **Run workflow** 按钮，点它
5. 再点一次绿色的 **Run workflow**（确认）

这样就开始编译了。

---

## 第 5 步：等待编译（3~5 分钟）

1. 页面会出现一条正在运行的记录（黄色圆点表示在跑）
2. 点进去可以看实时日志
3. 等它变成**绿色对勾 ✓**，就编译成功了
4. 如果是**红色叉 ✗**，说明失败，看第 7 步

---

## 第 6 步：下载 APK

1. 编译成功后，在那条记录页面往下滚
2. 找到 **Artifacts** 区域
3. 点 **LocalShare-Android-APK**
4. 会自动下载一个 `.zip` 压缩包
5. 解压，里面就是 **`app-debug.apk`**

> Artifacts 保留 30 天，过期会自动删除，但可以重新编译。

---

## 第 7 步：装到手机

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / QQ / 数据线都行）
2. 在手机上点开这个 apk
3. 会提示「未知来源应用」，允许一次
4. 安装完成

第一次打开 App 时，按提示做授权（推荐 Shizuku，没有就用无障碍服务）。

---

## 常见问题

> 已知坑已修复：早期版本的配置会用 `android-actions/setup-android`，
> 它在新版 SDK 上会报 `Failed to find package 'tools'`。
> 现在的工作流已改为手动安装 SDK 组件，不再依赖那个 action。

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| GitHub 打不开 | 网络问题 | 换时间重试，或用手机热点 |
| 上传后 Actions 里没有 Build APK | `.github` 文件夹没传上去 | 检查隐藏文件是否显示，或走第 3 步备用方案 |
| 编译报红叉 | 代码或配置问题 | 点进记录看红色日志，**把报错发给我** |
| `Failed to find package 'tools'` | 旧版配置用的 setup-android 已失效 | 已修复，请用最新的 `.github/workflows/build-apk.yml` |
| `You must either assign id's to all methods or to none of them` | AIDL 方法编号不一致 | 已修复（`IShellService.aidl` 的 `exec` 补了 id） |
| `Daemon will be stopped at the end of the build` | **不是错误** | `--no-daemon` 的正常提示，忽略即可 |
| `incompatible with attribute accessibilityEventTypes` | 无障碍事件类型名写错 | 已修复（`typeViewFocusChanged` 应为 `typeViewFocused`） |
| `Android resource linking failed` | 某个资源属性值非法 | 看 ERROR 行里指出的文件名和属性名 |
| `Returns are not allowed for functions with expression body` | Kotlin 表达式体（`= try{}`）里写了 return | 已修复，改为块体 |
| `Unresolved reference: OPSTR_READ_CLIPBOARD` | 该 AppOps 常量在公开 SDK 里不可见 | 已修复，改用字面量 `"android:read_clipboard"` |
| `No value passed for parameter 'p2'` | Shizuku 的 `unbindUserService` 需要 3 个参数 | 已修复，补第三参数 `true`（同时销毁特权进程） |
| Artifacts 是空的 | APK 路径不对 | 把报错发给我 |
| 仓库设成了 Private | Actions 额度受限 | 改成 Public：Settings → 最下方 Change repository visibility |
| 手机装不上 | 未允许未知来源 | 手机设置里允许「安装未知应用」 |

---

## 这个方案的原理（不感兴趣可以跳过）

GitHub 提供免费的计算资源（GitHub Actions）。我的配置文件
`.github/workflows/build-apk.yml` 告诉服务器：

1. 准备 Ubuntu 系统
2. 装 JDK 17
3. 装 Android SDK
4. 装 Gradle 8.0
5. 执行 `gradle assembleDebug`
6. 把生成的 APK 存起来供下载

整个过程在 GitHub 的服务器上跑，**完全不占你的电脑**。

---

## 对比三种方案

| 方案 | 装环境 | 耗时 | 适合 |
| --- | --- | --- | --- |
| **GitHub Actions（本文）** | 0 | 首次 30 分钟 | 不想装环境、偶尔编译 |
| Android Studio | 6~10 GB | 首次 1~2 小时 | 打算长期开发 |
| 电脑端网页方案 | 0 | 0 | 不需要手机自动同步 |

---

## 如果 GitHub 用不了

还有这些思路（都比较折腾，按需选择）：

1. **国产代码托管平台的 CI**：Gitee（码云）、CODING 都提供类似的流水线功能，
   操作方式差不多，但需要自己写配置
2. **云主机**：租一台最低配云服务器（几块钱一小时），装环境编译完就释放
3. **找朋友代编译**：把 `LocalShare-Android` 文件夹发给装了 Android Studio 的朋友

但说实话，如果你只是偶尔用，**电脑端网页方案（零安装）可能已经够用了**，
只是「手机 → 电脑」方向需要在网页里粘贴一下再点推送。
