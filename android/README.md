# OpenFic for Android

OpenFic 的 Android 客户端，同时适配**手机**与**平板**。

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

`android/app/build.gradle.kts` 里的 `copyFrontendDist` 任务会把 `frontend/dist/` 复制到
`app/src/main/assets/www/`。该目录是构建产物，已加入 `.gitignore`；如果 `frontend/dist`
不存在，构建会直接报错并提示你先构建前端。

用 Android Studio 打开 `android/` 目录也可以，Gradle 同步后直接运行即可。

## 使用

首次启动会进入服务器配置页：

1. 填入后端地址，例如 `192.168.1.10:8000`、`nas.local:8000` 或 `https://openfic.example.com`。
   不写协议时默认按 `http://` 处理。
2. 点「测试连接」——它会请求 `GET /api/v1/health`（后端上该接口无需鉴权），确认地址可达并显示服务器版本。
3. 点「连接」。

之后可以在两个地方改地址：

- 连接失败页上的「修改服务器地址」
- 前端设置页（`openficAndroidHost` 桥接会调用原生配置页）

## 界面来源

配置页可以选界面由谁提供：

| 选项 | 说明 |
| --- | --- |
| **内置界面**（默认） | 使用 APK 里打包的前端。启动快，后端没有提供静态前端时也能用。**代价**：页面来源与后端跨域，若后端设置了 `OPENFIC_AUTH_PASSWORD`，`SameSite=Lax` 的登录 Cookie 无法附带，会登录不上。 |
| **服务器界面** | 直接加载后端提供的同一份前端（后端在 `/` 上托管了它）。与后端完全同源，Cookie 正常，界面也随后端更新自动同步。**代价**：首屏需要一次网络往返。 |

默认部署（不带密码的 Docker / `openfic serve`）用「内置界面」即可。**如果你的后端设了访问密码，请选「服务器界面」。**

## 已做的适配

- **沉浸式与安全区**：`MainActivity` 用 `WindowCompat.setDecorFitsSystemWindows(false)` 开启 edge-to-edge，再把系统栏与输入法的 inset 作为 padding 施加到 WebView 上。这样整个 Web 视口始终位于安全区内——刘海屏、手势导航条、弹出键盘都不会遮挡内容，前端 CSS 一行都不用改。
- **状态栏配色**：前端通过 `openficAndroidHost.publishAppearance` 上报主题，原生侧据此调整系统栏图标明暗与状态栏底色，和 App 内主题保持一致。
- **返回键**：优先走 WebView 历史，到根再退出，符合 Android 习惯。
- **文件选择**：`onShowFileChooser` 接系统文件选择器（封面、角色图、附件上传）。
- **下载**：`DownloadListener` 交给系统 `DownloadManager`（导出文稿等）。
- **外链**：非本站、非后端的 http(s) 链接交给系统浏览器，不会把 App 导航走。
- **平板**：不锁定方向、不限屏幕尺寸，`resizeableActivity` 打开，支持分屏；布局沿用前端既有的 768px 断点。
- **纯 HTTP 后端**：LAN 上的后端通常是明文 HTTP，而内置页面来自 secure origin，所以放开了 mixed content 与 cleartext（见 `res/xml/network_security_config.xml`）。

## 对上游前端做的改动

只有两处，都是让前端能识别 Android 宿主：

- `frontend/src/pwa/register-sw.ts` — 检测到 `openficAndroidHost` 时跳过 Service Worker 注册。资源本来就来自 APK，SW 没有收益，反而会让缓存跨版本残留。
- `frontend/src/lib/desktop-appearance-bridge.ts` — 新增 `openficAndroidHost` 类型声明，并让三个 `publish*` 函数同时上报给 Android 宿主。

## 目录结构

```
android/
├── app/src/main/
│   ├── java/com/openfic/android/
│   │   ├── MainActivity.kt              WebView 宿主：加载、探活、返回键、下载、文件选择
│   │   ├── ServerSetupActivity.kt       服务器地址配置页
│   │   ├── storage/
│   │   │   └── AppPreferences.kt        地址与界面来源的持久化
│   │   └── web/
│   │       ├── OpenFicAssetHandler.kt   静态资源 + /runtime-config.json 拦截
│   │       ├── AndroidHostBridge.kt     openficAndroidHost JS 桥接
│   │       └── BackendProbe.kt          GET /api/v1/health 探活
│   ├── assets/www/                      前端产物（构建时生成）
│   └── res/
└── tools/svg_to_vector.py               品牌 SVG → Android 矢量图标
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
