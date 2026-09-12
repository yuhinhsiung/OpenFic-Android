# OpenFic for Android

[OpenFic](https://github.com/syrizelink/OpenFic) 的 Android 客户端，同时适配**手机**与**平板**。

本仓库（`OpenFic-Android`）是从上游 fork 出来的，只承载安卓客户端这一侧的改动，
**不向上游提交任何内容**；上游更新用 `git fetch upstream && git merge upstream/main` 合并进来。

与桌面版不同，Android 端**不内置后端**：它连接你自己运行的 OpenFic 服务（Docker、`openfic serve`，或桌面版拉起的本地服务）。项目数据仍然只保存在那台服务器上，手机不落一份。

## 工作原理

实现上复刻了桌面版 Electron 的「远程实例」模式，而不是另起一套通信机制：

1. React 前端打包进 APK，由 `WebViewAssetLoader` 从 `https://appassets.androidplatform.net/` 提供。这是一个 **secure origin**，因此 localStorage / IndexedDB / Cookie 的行为与普通网页一致。
2. 该 origin 下的 `/runtime-config.json` 被 `OpenFicAssetHandler` 拦截，返回用户填写的后端地址（对应 Electron 的 `desktop/src/main/protocol.ts`）。
3. 前端 `frontend/src/lib/runtime-config.ts` 启动时读取该文件，并把它作为 axios 与 Socket.IO 的唯一地址来源。

也就是说，**连接后端这件事前端本来就已经支持**——它原本就是为桌面版远程模式写的。Android 端要做的只是把同一份配置文件喂给它。

```
┌─────────────────────── APK ───────────────────────┐
│  MainActivity                                     │
│    └─ WebView                                     │
│         └─ https://appassets.androidplatform.net/ │
│              ├─ /            → assets/www/ (SPA)  │
│              └─ /runtime-config.json → {后端地址}  │
└───────────────────────────────────────────────────┘
                        │  axios + Socket.IO
                        ▼
        http://<你的服务器>:8000/api/v1, /socket.io
```

## 构建

前置条件：

- JDK 17
- Android SDK（`platforms;android-35`、`build-tools;35.0.0`、`platform-tools`）
- Node.js + pnpm（用于构建前端）

```bash
# 1. 构建前端产物（android 模块会把它打包进 APK）
cd frontend
pnpm install
pnpm build

# 2. 构建 APK
cd ../android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 上在 PowerShell 或 cmd 里用 `.\gradlew.bat assembleDebug`，效果相同。第一次运行
会下载 Gradle 发行包（约 130 MB）。

### 发布签名

`assembleRelease` 从 `android/keystore.properties` 读取签名信息（连同 `android/keystore/` 下的
密钥库文件，两者都已加入 `.gitignore`，**不会进版本库**）：

```properties
storeFile=keystore/openfic-release.jks
storePassword=…
keyAlias=openfic
keyPassword=…
```

这两个文件是**可选的**：没有它们时 `assembleRelease` 依然能跑，只是产出未签名的 APK，
所以任何人 clone 下来都能自行构建。只有持有密钥库的人才能产出可安装的正式包。

> [!WARNING]
> **密钥库一旦丢失，就无法再发布能覆盖安装的更新** —— Android 只接受用同一把密钥签名的
> 升级包，届时用户必须先卸载再装。请把 `android/keystore/` 和 `keystore.properties`
> 备份到仓库之外的安全位置。

验证签名：

```bash
"$JAVA_HOME/bin/java" -jar "$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar" verify --print-certs app-release.apk
```

（在 Windows 的 git-bash 下不要用 `apksigner.bat`：本仓库路径含中文，cmd.exe 会把它变成乱码。）

### 发布流程

1. **同时递增 `versionCode` 和 `versionName`**（`app/build.gradle.kts`）。

   `versionCode` 必须严格递增，否则 Android 会拒绝覆盖安装 —— 这台设备上装了旧版就直接报错，
   用户必须先卸载。`versionName` 则用于界面显示和应用的「检查更新」比对。

   版本号沿用 `<上游版本>[.<构建号>]`：上游 0.11.1 上的首个发布是 `0.11.1`，其后的补丁发布是
   `0.11.1.2`、`0.11.1.3`…… 换到新的上游基线就回到两段（例如上游 0.12.0 → `0.12.0`）。
   这样既看得出基于哪版上游，又不会和上游的 `v*` tag 撞车。

   应用的版本比对是按数字逐段进行的（`parseVersion` 提取所有数字段），所以
   `0.12.0` 会被正确判定为比 `0.11.1.3` 新，`0.11.1.10` 高于 `0.11.1.9`。

2. 构建：`.toolchain/build.sh assembleRelease`（或 `./gradlew assembleRelease`）
3. 打 tag：`android-v<版本号>`（`android-` 前缀用于避开上游的 `v*` tag）
4. 在 GitHub 上创建 Release 并附上 `app-release.apk`

> [!IMPORTANT]
> **发布后务必自己装一遍。** `assembleRelease` 与 `assembleDebug` 是两条不同的构建路径
> （签名不同、`BuildConfig.DEBUG` 不同、WebView 调试关闭），而发布包又是不可调试的
> （`run-as` 会被拒绝），出问题时排查手段比 debug 版少得多。

`android/app/build.gradle.kts` 里的 `copyFrontendDist` 任务会把 `frontend/dist/` 复制到
`app/src/main/assets/www/`。该目录是构建产物，已加入 `.gitignore`；如果 `frontend/dist`
不存在，构建会直接报错并提示你先构建前端。

用 Android Studio 打开 `android/` 目录也可以，Gradle 同步后直接运行即可。

## 使用

### 实例管理

安卓端复刻了桌面版的**实例菜单**：可以保存多个后端、随时切换、编辑或删除。入口有两处：

- 前端**设置 → 通用 → 后端实例 → 管理实例**
- 连接失败页上的「修改服务器地址」

首次启动（还没有任何实例时）会直接进入添加实例页。

添加一个实例：

1. **名称**可留空，会默认使用主机名，例如 `nas.local:8000`
2. 填入后端地址，例如 `192.168.1.10:8000`、`nas.local:8000` 或 `https://openfic.example.com`。
   不写协议时默认按 `http://` 处理
3. 点「测试连接」——它请求 `GET /api/v1/health`（后端上该接口无需鉴权），确认可达并显示服务器版本
4. 保存

列表里点一行即切换到该实例；右侧铅笔图标进入编辑，编辑页底部可以删除。

**切换实例会清空本地缓存并重新加载。** 因为所有实例都从同一个页面来源
（`appassets.androidplatform.net`）提供服务，而前端把项目标签页、最近项目、未保存的写作缓冲存在
IndexedDB 里 —— 这些按项目 id 索引，属于**上一个**后端。不清掉就会串数据。

### 从旧版本升级

单个地址的老配置会在首次启动时自动迁移成一个实例，名称取主机名，无需手动重填。

## 界面来源

**每个实例单独设置**——因为是否需要它取决于后端自身（是否设了访问密码）：

| 选项 | 说明 |
| --- | --- |
| **内置界面**（默认） | 使用 APK 里打包的前端。启动快，后端没有提供静态前端时也能用。**代价**：页面来源与后端跨域，若后端设置了 `OPENFIC_AUTH_PASSWORD`，`SameSite=Lax` 的登录 Cookie 无法附带，会登录不上。 |
| **服务器界面** | 直接加载后端提供的同一份前端（后端在 `/` 上托管了它）。与后端完全同源，Cookie 正常，界面也随后端更新自动同步。**代价**：首屏需要一次网络往返。 |

默认部署（不带密码的 Docker / `openfic serve`）用「内置界面」即可。**如果某个后端设了访问密码，把那个实例设成「服务器界面」。**

这不是取巧，是浏览器的硬性规则：内置界面下页面来源是 `appassets.androidplatform.net`，而接口在你的服务器上，两者跨站；后端下发的登录 Cookie 标了 `SameSite=Lax`，跨站请求不会附带它。实测的现象是登录接口返回 200、`Set-Cookie` 也下发了，但后续每个请求在服务端看到的都是「未认证」。而 `SameSite=None` 要求 Cookie 必须同时是 `Secure`，也就是必须 HTTPS —— 局域网后端通常做不到。

服务界面下页面与接口同源，Cookie 是第一方的，一切正常。应用会在检测到这种情况时直接提示并支持一键切换。

## 已做的适配

- **沉浸式与安全区**：`MainActivity` 用 `WindowCompat.setDecorFitsSystemWindows(false)` 开启 edge-to-edge，再把系统栏与输入法的 inset 作为 padding 施加到 WebView 上。这样整个 Web 视口始终位于安全区内——刘海屏、手势导航条、弹出键盘都不会遮挡内容，前端 CSS 一行都不用改。
- **状态栏配色**：前端通过 `openficAndroidHost.publishAppearance` 上报主题，原生侧据此调整系统栏图标明暗与状态栏底色，和 App 内主题保持一致。
- **返回键**：优先走 WebView 历史，到根再退出，符合 Android 习惯。
- **文件选择**：`onShowFileChooser` 接系统文件选择器（封面、角色图、附件上传）。
- **下载**：`DownloadListener` 交给系统 `DownloadManager`（导出文稿等）。
- **外链**：非本站、非后端的 http(s) 链接交给系统浏览器，不会把 App 导航走。
- **平板**：不锁定方向、不限屏幕尺寸，`resizeableActivity` 打开，支持分屏；布局沿用前端既有的 768px 断点。
- **纯 HTTP 后端**：LAN 上的后端通常是明文 HTTP，而内置页面来自 secure origin，所以放开了 mixed content 与 cleartext（见 `res/xml/network_security_config.xml`）。
- **原生界面跟随应用语言**：语言是同步到后端的应用设置，由前端通过 `openficAndroidHost.publishLanguage` 上报、原生侧持久化，各 Activity 在 `attachBaseContext` 里套一层对应 locale。否则应用设成中文、设备是英文时，原生界面会一直显示英文。首次启动（还没连过任何后端、前端尚未运行）会回退到系统语言。
- **检查更新**：启动时自动检查一次、设置里也可手动触发，比对 GitHub Releases 的版本号。发现新版本时弹原生对话框，**优先走应用内下载**；DownloadManager 在无法访问 GitHub 的网络下会长时间静默无进展，所以会观察十几秒的实际字节数 —— 有进展就交给完成通知，失败或没动静则提示改用浏览器（浏览器能走用户已有的代理）。
- **密码后端的引导**：内置界面在密码后端上必然登录失败（见「界面来源」），应用会在加载前先探测 `auth/status`，命中就直接弹出说明并提供**一键切到服务器界面**，而不是把用户丢在一个怎么输都失败的登录页上。

## 对上游前端做的改动

**宿主识别**（让前端知道自己在 Android 里）：

- `frontend/src/pwa/register-sw.ts` — 检测到 `openficAndroidHost` 时跳过 Service Worker 注册。资源本来就来自 APK，SW 没有收益，反而会让缓存跨版本残留。
- `frontend/src/lib/desktop-appearance-bridge.ts` — 新增 `openficAndroidHost` 类型声明，并让三个 `publish*` 函数同时上报给 Android 宿主。

**实例管理入口**：

- `frontend/src/features/settings/components/general-settings.tsx` — 在「通用」设置里加一节「后端实例」，点击调用 `openficAndroidHost.openInstanceManager()`。桌面外壳的实例菜单在窗口 chrome 上，安卓端没有对应位置，所以在设置页给出入口。
- `frontend/src/i18n/locales/{zh-CN,en}.json` — 对应文案。

**窄屏排版修复**（详见 `MOBILE-LAYOUT-QA.md`）：

- `frontend/src/components/number-flow-safe.tsx`（新增）+ 4 处导入替换 — `@number-flow/react` 依赖 CSS `round()`/`mod()`（Chromium 125+），旧版 WebView 上数字会重叠。不支持时降级为纯文本。
- `frontend/src/components/title-input.tsx` — 移动端改用自增高 `<textarea>`，文档大标题才能换行。
- `frontend/src/hooks/use-mobile-viewport.ts`（新增）— 供共用组件匹配 app shell 的移动端断点。
- `frontend/src/features/projects/components/project-list-item.tsx` — 元信息项加 `nowrap`，不再从词中间断行。
- `frontend/src/features/world-info/{components/entry-list-item.tsx,pages/world-info-page.css}` — 条目标题移动端两行 + 缩小字号。
- `frontend/src/features/dashboard/pages/dashboard-page.css` — 移动端统计卡片等宽、日历加滚动提示。
- `frontend/src/features/assistant/components/assistant-sidebar.css` — 会话标题移动端两行。

## 目录结构

```
android/
├── app/src/main/
│   ├── java/com/openfic/android/
│   │   ├── MainActivity.kt              WebView 宿主：加载、探活、返回键、下载、文件选择
│   │   ├── InstancesActivity.kt         实例列表：切换 / 添加 / 编辑入口
│   │   ├── InstanceEditActivity.kt      实例表单：名称、地址、探活、界面来源、删除
│   │   ├── storage/
│   │   │   └── AppPreferences.kt        实例列表与激活项的持久化（含旧配置迁移）
│   │   └── web/
│   │       ├── OpenFicAssetHandler.kt   静态资源 + /runtime-config.json + 存储重置页
│   │       ├── AndroidHostBridge.kt     openficAndroidHost JS 桥接
│   │       └── BackendProbe.kt          GET /api/v1/health 探活
│   ├── assets/www/                      前端产物（构建时生成）
│   └── res/
├── tools/svg_to_vector.py               品牌 SVG → Android 矢量图标
└── MOBILE-LAYOUT-QA.md                  窄屏排版问题排查记录与测试方法
```

> 这里没有 `data/` 包是有意为之：仓库根目录的 `.gitignore` 里有一条裸的 `data` 规则
> （用于忽略后端运行时的数据目录），它会连任何层级的 `data/` 目录一起忽略掉。

## 验证情况

**已在模拟器上端到端验证通过**（Android 15，手机 + 平板各一台）：

| 项目 | 结果 |
| --- | --- |
| 手机（1080×2400，Pixel 6 规格） | 启动 → 配置服务器 → 连接 → 界面渲染，底部显示「已连接」 |
| 平板（2560×1600，约 1280dp 宽） | 同上，且宽度超过 768px 断点，自动使用桌面布局（常驻侧栏、新建项目/导入、最近编辑） |
| 内置前端从 APK 加载 | 正常（`WebViewAssetLoader` 从 `assets/www` 提供，JS/CSS/字体全部 200） |
| `/runtime-config.json` 注入 | 正常，返回用户填写的后端地址 |
| 原生探活 | 正常，配置页「测试连接」显示服务器版本 |
| REST + Socket.IO | 正常，后端收到来自 `https://appassets.androidplatform.net` 的完整请求序列 |
| 主题同步 | 正常，前端通过 `openficAndroidHost.publishAppearance` 上报后，系统栏区域跟随 App 主题变色 |
| 返回键 / 安全区 | 未见异常 |

构建产物：`assembleDebug` 通过，APK 33 MB，含 732 个前端资源。`aapt2 dump badging` 确认
`minSdk 26` / `targetSdk 35`，`supports-screens` 覆盖 small…xlarge。前端 `pnpm type-check` 通过。

### 移植过程中修掉的两个真实缺陷

这两个都只有在真机/模拟器上才会暴露，记录在此以免回退：

1. **入口路径必须是 `/` 而不是 `/index.html`。**
   前端用 react-router 匹配 `location.pathname`，加载 `/index.html` 会匹配不到任何路由，
   页面渲染为空白（控制台报 `No routes matched location "/index.html"`）。见
   `OpenFicAssetHandler.BASE_URL`。

2. **`WebViewAssetLoader` 传给 `PathHandler` 的是去掉注册前缀后的路径。**
   处理函数注册在 `"/"` 上时，请求 `/runtime-config.json` 到达时是 `runtime-config.json`
   （无前导斜杠）。早期版本按带斜杠比较，导致该请求落到「文件不存在」分支并返回 null，
   WebView 转去走网络而失败，前端随即回退到相对路径 `/api/v1`，整个应用连不上后端。
   现已统一 `trimStart('/')` 后比较。

### 关于本机模拟器

本机一开始跑不起来模拟器：进程存活、端口 5554/5555 在监听，但 CPU 时间停在 0.34 秒不再增长，
`adb` 始终 `offline`——看起来像 WHPX / Hyper-V 故障，其实不是。

根因是 **SDK 装在非 ASCII 路径下**（本仓库路径含 `多端同步`）：qemu 子进程加载
`bios-256k.bin` 失败，而 emulator 37.1.11 把这个错误吞掉之后直接挂起，所以看不到任何提示。
把 SDK 移到 `D:\android-sdk`、AVD 移到 `D:\android-avd` 后立即恢复正常，
**模拟器版本无关**（36.4.10 与 37.1.11 都可用）。

排查这类「静默挂起」的一个有效手段：换一个旧版模拟器启动，它往往会把被新版吞掉的真实错误
（这里是 `qemu: could not load PC BIOS 'bios-256k.bin'`）打印出来。

### 尚未在真机验证

模拟器不能覆盖的部分：

- 文件上传（`onShowFileChooser`）与下载（`DownloadManager`）——模拟器上未走通系统选择器
- 刘海屏 / 手势导航条下的安全区实际观感
- 真实局域网下的连接稳定性

## 已知限制

- **带密码的后端**需要选「服务器界面」（原因见上）。
- **不支持离线**：没有后端时 App 只能打开外壳，任何数据操作都会失败——这是客户端-服务端架构决定的。
- 前端的**键盘快捷键**（如 `Ctrl+M`）在触屏上无对应入口，相关操作需要用界面按钮完成。
- 内置界面与后端版本可能不一致。如果后端升级后界面出现异常，切到「服务器界面」即可。
