# 移动端排版排查与修复

记录这次针对窄屏排版问题的排查过程、根因与修复，以及可复用的测试方法。

测试环境：Android 15 模拟器，手机 1080×2400（CSS 视口 412px）/ 平板 2560×1600（约 1280dp），
后端为真实服务（0.11.0）。排查手段见文末「测试方法」。

## 结论速览

| # | 现象 | 根因 | 影响面 |
| --- | --- | --- | --- |
| 1 | 配置页文字与输入框贴着屏幕边缘 | 原生代码用 `updatePadding` **覆盖**了布局里 24dp 的内边距 | 手机 + 平板 |
| 2 | Agent 会话抬头 `Tokens ↑813.8K ↓25.8M` 数字互相挤在一起 | `@number-flow/react` 依赖 CSS `round()`/`mod()`，旧版 WebView 不支持 | 手机 + 平板 |
| 3a | 仪表盘「2026年创作了1天」数字重叠 | 同 #2 | 手机 + 平板 |
| 3b | 仪表盘统计卡片一大一小（120px vs 248px） | 桌面端刻意的 3/3/2/4 错落布局，在 412px 下失去视觉逻辑 | 手机 |
| 3c | 仪表盘年度日历被右侧截断 | 日历固定 780px + 容器横向滚动，手机上看不出可滑动 | 手机 |
| 4a | 世界书**条目列表**的长标题被省略号截断 | 条目标题写了 `whiteSpace: nowrap` | 手机 |
| 4b | 世界书**文档大标题**被截断 | 共用组件 `TitleInput` 用的是单行 `<input>`，从设计上不能换行 | 手机 |
| 5 | 项目列表卡片元信息从词中间断行（「3,113 / 字」） | flex 子项可收缩 + 中文可任意断行，三项自然宽度 170px 塞进 164px | 手机 |
| 6 | 横屏转竖屏后主菜单卡在屏幕上，点不掉也划不走 | 侧边栏同时兼做桌面常驻栏与移动抽屉，motion 动画 `x` 时把 `transform` 写成**内联**样式，压过了抽屉的类选择器 | 手机 |

---

## 1. 配置页边距

`ServerSetupActivity.applyWindowInsets()` 把系统栏 inset 作为 padding 施加到内容视图上：

```kotlin
content.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
```

`updatePadding` 是**替换**语义。竖屏下 `bars.left/right` 为 0，于是布局 XML 里
`android:padding="24dp"` 的左右内边距被清零，所有内容贴到屏幕边缘；顶部因为
`bars.top` 是状态栏高度，反而看起来正常。

**修复**：保留布局里的基准 padding，只在其上叠加 inset。顺带处理平板——表单原本会铺满
2560px 宽，现在按内容最大宽度（560dp）把左右留白撑开，超宽屏上居中成可读的一列。

## 2. Agent 会话抬头数字重叠（#3a 同根因）

`@number-flow/react` 把每个数字渲染成一条 `0123456789` 的垂直条带，再用 CSS 遮罩裁出
当前数字、并用负 margin 抵消遮罩占位。这套样式的有效性依赖两个 CSS 数学函数：

```css
-webkit-mask-size: ... calc(round(nearest, calc(var(--number-flow-mask-height, .25em) / 2), 1px) * 2) ...;
.digit__num { --offset-raw: mod(...); }
```

`round()` 与 `mod()` 是 Chrome 125 才加入的。模拟器的 WebView 是 **Chromium 124**，
两个函数都不支持 → 整条 `-webkit-mask-size` / `-webkit-mask-image` 声明失效
（`mask-image` 计算值为 `none`）→ 数字条带失去裁剪与宽度补偿。

实测（会话抬头）：

```
hostW 25.9  numberW 30.3   maskImage: none   OVERLAP +4.4px
hostW 22    numberW 23.5   maskImage: none   OVERLAP +1.5px
hostW 0     numberW 6.8    maskImage: none   OVERLAP +6.8px   ← 宿主宽度为 0
```

宿主宽度塌缩（甚至为 0），后面的内容就叠了上来。

**为什么桌面端看不到**：桌面浏览器是 Chrome 152，两个函数都支持。
国内相当多的 Android 设备没有 Play 商店，WebView 无法更新，会长期停留在旧版本——
所以这不是模拟器特有的问题，需要防御。

**修复**：新增 `SafeNumberFlow` 组件。检测 `CSS.supports("width", "round(nearest, 1px, 1px)")`，
支持则原样渲染 `NumberFlow`（保留滚动动画），不支持则用 `Intl.NumberFormat` 输出同样格式的
纯文本。几何正确优先于动画。

## 3b. 仪表盘统计卡片宽度不均

```css
.dashboard-writing-stack { grid-template-columns: repeat(6, minmax(0, 1fr)); }
.dashboard-writing-stack .dashboard-writing-stat-card:nth-child(1),
.dashboard-writing-stack .dashboard-writing-stat-card:nth-child(2) { grid-column: span 3; }
.dashboard-writing-stack .dashboard-writing-stat-card:nth-child(3) { grid-column: span 2; }
.dashboard-writing-stack .dashboard-writing-stat-card:nth-child(4) { grid-column: span 4; }
```

这是桌面端刻意的错落排版。移动端断点（`max-width: 767px`）只调了 gap 和卡片高度，
**保留了 6 列结构**，于是在 412px 视口里变成 120px 与 248px 两张卡并排。

**修复**：在移动端断点下改为等宽两列。

## 3c. 仪表盘年度日历截断

```css
.dashboard-nivo-calendar { width: 780px; min-width: 780px; }
.dashboard-calendar-frame.dashboard-calendar-frame-yearly { overflow-x: auto; }
```

一年 365 格在桌面上确实需要 780px，容器横向滚动是有意设计。手机上问题不在"滚动"，
而在于**看不出可以滚动**——画面右侧被硬切，视觉上像渲染坏了。

**没有选择把日历压到一屏**：360px 里塞 365 格，每格不到 1px，热力图会退化成一条色带，
反而失去意义。保留滚动，只给它加上可滚动的视觉提示：移动端在滚动容器右缘加一道渐隐。

```css
@media (max-width: 767px) {
  .dashboard-calendar-frame-yearly {
    -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 24px), transparent 100%);
    mask-image: linear-gradient(to right, #000 calc(100% - 24px), transparent 100%);
  }
}
```

## 4. 长标题被截断

**有两处，第一轮只修了其中一处**，用户复核时指出另一处仍然存在 —— 记录在此以免再漏。

### 4a. 世界书条目列表

`features/world-info/components/entry-list-item.tsx`：

```tsx
<Text style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", ... }}>
  {entry.name}
</Text>
```

`whiteSpace: nowrap` 明确禁止换行。真实数据里
「战略地理：汴京、河北、河东、燕云、陕西、江淮」在 276px 可用宽度下被截成
「战略地理：汴京、河北、河东、燕…」。

**修复**：移动端允许换行，最多两行（`-webkit-line-clamp: 2`），字号从 `size="2"`（14px）
降到 `size="1"`（12px）。桌面保持单行省略不变——宽屏下它不是问题，改了反而破坏列表的行高节奏。

字号那条规则需要写成 `.world-info-entry-name.world-info-entry-name`（重复类名）：元素同时带着
Radix 的 `rt-r-size-2`，两者特异性相同，而 Radix 的样式表在打包后更靠后，普通写法会输掉这场竞争。

### 4b. 文档大标题（`TitleInput`）

`src/components/title-input.tsx` 是一个**共用组件**，写作编辑器和世界书编辑器都用它，
渲染的是单行 `<input type="text">`，字号 `calc(var(--font-size-editor) * 2)` = 32px。
**`<input>` 从设计上就不能换行**，所以 24 个汉字的标题（内容宽 704px、盒子宽 356px）只能被截断。

**修复**：移动端换成自增高的 `<textarea>`，字号降到 `* 1.5`（24px）。两处细节：

- 高度由 `useLayoutEffect` 按 `scrollHeight` 驱动，固定 `rows` 要么裁掉内容、要么在标题下方留一块空白
- 标题在逻辑上仍是单行：Enter 触发失焦而不是插入换行，避免下游到处要去掉换行符

长按相关的指针处理逻辑原样保留，`<textarea>` 上同样适用。

---

## 5. 项目列表卡片的元信息换行

`features/projects/components/project-list-item.tsx`，真实数据下的表现：

```
"3,113 字"      宽 42.7px  高 32px（两行）
"1 章"          宽 20.9px  高 32px（两行）
"大约 23 小时前" 宽 76.5px  高 32px（两行）
```

三项的自然宽度合计 170px，而容器（缩略图 60px + 操作按钮 76px 之外的文本列）只有 164px。
flex 子项默认可收缩，中文又能在任意字符间断行，于是渲染成
「3,113 / 字」「1 / 章」「大约 23 小时 / 前」。

**修复**：每项加 `whiteSpace: nowrap`，整行加 `wrap="wrap"`。
常见情况仍是一行；放不下时按**项**换行，而不是从词中间断开。
没有选择缩小 gap 硬塞成一行——那只差 6px，字号或字数稍有变化就会再次溢出。

---

## 6. 横竖屏切换后主菜单卡住

**现象**：设备开着方向自动切换，横屏转回竖屏后，主菜单（侧边栏）停在屏幕最前面不走，
既点不掉也划不走。

**根因**：侧边栏是**同一个元素**兼做两种形态——宽屏下是常驻栏，窄屏下是
`.mobile-sidebar-sheet` 抽屉——而它是个 motion 元素：

```tsx
animate={!isMobile ? { x: 0, width: sidebarWidth } : undefined}
```

motion 只要动画过任何一个 transform 属性，就会把 `transform` 注册成**内联样式**，
此后每次渲染都按 `buildTransform(state)` 重写一遍；`x` 停在 0 时写出来的值就是 `none`。
而抽屉的开合完全靠类选择器：

```css
.mobile-sidebar-sheet { transform: translateX(-100%); }
.mobile-sidebar-sheet[data-open="true"] { transform: translateX(0); }
```

内联样式优先级高于类选择器，所以这个元素**只要经历过一次桌面形态**（内联
`transform: none` 被写下），再回到窄屏时 `translateX(-100%)` 就永远不生效了，
抽屉停在 `x=0` 挡在内容前面。此时 `data-open` 是 `false`：没有遮罩可点，`pointer-events`
还是 `none`，连滑动关闭的判定都进不去——所以"无法收起"。

**为什么偏偏是横屏转竖屏**：1080×2400 的手机横屏视口宽 915px，越过 768px 断点走桌面布局；
转回竖屏 412px 才回到移动布局。菜单是**在横屏时被写死、转回竖屏才暴露**。
平板两种方向都 > 768px，不跨断点，因此不受影响。

这个缺陷在桌面版上同样成立：把窗口从宽拖窄到 768px 以下即可复现。安卓端只是更容易撞上，
因为手机上转个方向就会跨越断点。

**修复**：把这一层从 motion 元素换成普通 `Box`，让两种形态都不再有内联样式与类选择器之争。
桌面端的宽度动画改由 CSS 过渡承担（`transition: width 0.24s cubic-bezier(...)`，缓动与原来一致），
移动端保持纯 CSS——与其余四个页面的抽屉写法一致。

一个容易踩的细节：`.mobile-sidebar-sheet` 里的 `transition: transform` 会被内联 `transition`
**整体替换**（简写属性覆盖），所以内联里必须把 transform 一并写上；桌面端则只留 width，
否则切换断点时会把"本来就常驻"的侧栏也滑一遍。

---

## 修复后的复核结果

手机（1080×2400，视口 412px）与平板（2560×1600，视口 1280px）各跑一遍，连接真实后端。

| 检查项 | 手机 | 平板 |
| --- | --- | --- |
| 配置页内边距 | 内容距屏幕边缘 24px；平板居为一列约 560dp | ✅ |
| 数字组件降级 | `<number-flow-react>` 0 个，全部为普通 span | ✅ 同样降级 |
| 会话抬头 Tokens 行 | 行不溢出（scrollWidth == clientWidth）；数字宽度 37.9 / 34 / 34 px | — |
| 仪表盘「2026 年创作了 1 天」 | 无重叠，间距正常 | ✅ |
| 仪表盘统计卡片 | 四张等宽 184px | 保持桌面错落：175 / 175 / 113 / 237 |
| 日历滚动提示 | 右缘渐隐生效 | 渐隐不应用（保留桌面表现） |
| 世界书长标题 | 12px、两行、全文可见 | 保持 14px 单行省略 |
| 横屏→竖屏后的侧边栏 | 停在视口外（`translateX(-100%)`），无遮挡 | 不跨断点，不受影响 |
| 竖屏下抽屉开合 | 展开、点遮罩收起均正常 | — |
| 菜单开着时转屏再转回 | 不残留，回到收起状态 | — |

平板列确认了移动端规则的作用域正确：断点用的是 `max-width: 767px`，与前端自身的
`isMobile` 断点一致，所以平板完整保留桌面排版。

第 6 条的复核分两层：桌面 Chromium（`setViewportSize` 跨断点）与模拟器真机（`user_rotation`
真实转屏）各跑一遍，并且都做了**修复前 / 修复后**的对照——只有修复前能复现，才能确认修的是它。

### 一处判断说明

仪表盘统计卡片里的 `0` 字形明显比 `1`/`2`/`3,113` 重。核对后确认**不是缺陷**：
四张卡片的字体设置完全一致（`JetBrains Mono` 24px/700），差异来自该字体本身的带点零设计。
未做改动。

---

## 测试方法

可复用的排查手段，本机环境相关细节见 `README.md`。

**观察真实画面** —— 设备截图是唯一可信的渲染结果：

```bash
adb exec-out screencap -p > shot.png
```

**测元素几何** —— WebView 开了调试（debug 构建），`adb forward` 后可直接用 CDP：

```bash
adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof com.openfic.android)
node .work/qa.mjs measure "<css-selector>"   # 盒子、字号、溢出、white-space
node .work/qa.mjs eval "<js>"                # 任意检查
```

判断"是否溢出"的通用招法：比较 `scrollWidth` 与 `clientWidth`，或找
`getBoundingClientRect().width > window.innerWidth` 的元素。

**特征检测优先于猜版本**：判断某个 CSS 特性能不能用，
`CSS.supports()` 比推断 WebView 版本可靠：

```js
CSS.supports("width", "round(nearest, 1px, 1px)")
```

**shadow DOM 要穿透**：`@number-flow/react` 等组件把内容放在 shadow root 里，
`querySelectorAll` 查不到，需要 `el.shadowRoot.querySelector(...)`；
`textContent` 同理不会跨越边界（该组件的 `textContent` 里看不到数字，只有普通文字）。

**注意 CDP 截图的坑**：`Page.captureScreenshot` 在 WebView 上返回的画面与实际渲染不符
（裁剪区尺寸对不上、出现平铺伪影）。**以 `adb exec-out screencap` 为准。**

**对比法定位环境差异**：同一份前端产物分别跑在桌面浏览器和 WebView 上，
如果只有后者出问题，方向就落在引擎能力差异上，而不是业务代码。

**不依赖真实后端的整套前端验证**：`.work/mock/server.js` 一个进程同时提供
`frontend/dist` 静态资源、`/runtime-config.json`、够用的 `/api/v1`（含 CORS，内置界面是跨域调用）
和一个 Socket.IO 端点，应用外壳就能完整初始化。

```bash
node .work/mock/server.js "$(cygpath -m "$PWD/repo/frontend/dist")" 8899
```

它按请求的 Host 回填 `runtime-config.json`，所以同一份服务桌面浏览器用 `127.0.0.1:8899`、
模拟器用 `10.0.2.2:8899`，不用改任何配置。

**跨断点转屏的自动化**：`.work/rotation-check.mjs` 用 Playwright 驱动系统 Edge
（`channel: "msedge"`，无需下载浏览器），把视口在 412×915 / 915×412 之间来回切，
并读出侧边栏的 `data-open`、内联 transform、计算后 transform 与包围盒；
`.work/rotation-sweep.mjs` 把同样的流程套在所有一级路由和设置对话框上，
判定规则是**元素自己公布的 `data-open` 必须与它是否真的在屏幕上一致**。

这条判定规则是刻意选的：起初用"是否拦截点击"当判据，结果**漏掉了这个 bug**——
卡住的抽屉是 `pointer-events: none`，不拦点击。是先用修复前的产物验证了脚本能报错，
才敢用它去下"别处没问题"的结论。
