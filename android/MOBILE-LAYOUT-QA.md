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
| 4 | 世界书长标题被省略号截断 | 条目标题写了 `whiteSpace: nowrap` + `textOverflow: ellipsis` | 手机 |

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

## 4. 世界书长标题被截断

`features/world-info/components/entry-list-item.tsx`：

```tsx
<Text style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", ... }}>
  {entry.name}
</Text>
```

`whiteSpace: nowrap` 明确禁止换行。真实数据里
「战略地理：汴京、河北、河东、燕云、陕西、江淮」在 276px 可用宽度下被截成
「战略地理：汴京、河北、河东、燕…」。

**修复**：移动端允许换行，最多两行（`-webkit-line-clamp: 2`），字号从 `size="2"` 降到
`size="1"`。桌面保持单行省略不变——宽屏下它不是问题，改了反而破坏列表的行高节奏。

同样的问题也出现在 Agent 会话标题（`New session - 2026-09-10T14:59:40....`），一并处理。

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

平板列确认了移动端规则的作用域正确：断点用的是 `max-width: 767px`，与前端自身的
`isMobile` 断点一致，所以平板完整保留桌面排版。

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
