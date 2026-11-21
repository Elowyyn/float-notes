# 浮窗便签 · Android

MD3 风格的系统级悬浮窗便签。浮窗挂载在 `WindowManager` 的 `TYPE_APPLICATION_OVERLAY` 层，可悬浮在桌面和任意 App 之上。**每个浮窗就是一张完整卡片**：顶部控制行（显示·隐藏 / 锁定·解锁 / 关闭 + 透明度滑块）→ 细分隔线 → 内容（文字或图片）。支持双指捏合缩放。

- 包名：`com.elowyyn.floatnotes`
- 版本：`1.6`（versionCode 7）
- minSdk 26 / targetSdk 34 / compileSdk 34
- 产物：`../浮窗便签_v1.6.apk`（自签名，可直接安装）
- 仓库：<https://github.com/Elowyyn/float-notes>（public，main 分支）
- 不入库：`android/build/`、`*.apk`、`*.idsig`、`.workbuddy/`（见 `.gitignore`）

## 技术栈

**纯原生 Java + 手工构建**，不依赖 Gradle、不依赖 AndroidX（无 Compose、无 Material 库）。
MD3 观感由自绘 drawable（圆角 + 描边 + 涟漪）和 framework VectorDrawable 图标实现。

## 目录

```
android/
├── AndroidManifest.xml        权限、Activity、前台服务声明
├── build.sh                   一键构建（aapt2 → javac → d8 → zipalign → apksigner）
├── src/com/elowyyn/floatnotes/
│   ├── MainActivity.java      应用内管理界面（列表 / 添加 / 编辑 / 删除 / 权限引导）
│   ├── OverlayService.java    前台服务，负责所有浮窗的 WindowManager 增删改
│   ├── WindowItem.java        数据模型 + JSON 序列化
│   ├── Store.java             唯一数据源（SharedPreferences JSON）
│   └── Ui.java                dp 换算、圆角背景、图标/按钮工厂
└── res/
    ├── values/, values-night/ 颜色令牌、主题（跟随系统深浅色）
    ├── drawable/ic_*.xml      11 个矢量图标
    └── mipmap-*/ic_launcher.png
```

## 构建

```bash
cd android && bash build.sh
```

脚本内写死本机 SDK / JDK 路径，换机需改 `SDK` 与 `JDK` 两个变量：

- SDK：`C:/Users/15697/.workbuddy/tools/android/sdk`（build-tools 34.0.0、platforms/android-34）
- JDK：`.../jdk/jdk-17.0.20.1+1`

签名证书首次构建时自动生成于 `build/float-notes.keystore`（alias `floatnotes`，密码 `floatnotes2026`）。**不要丢失**，否则无法覆盖安装。

### 推送 GitHub

仓库内已固定 `http.version = HTTP/1.1`（HTTP/2 经代理会 `unexpected eof`）。推送前先确认 FlClash 端口：

```bash
curl -s -o /dev/null -w "%{http_code}" -x "http://127.0.0.1:<port>" https://github.com   # 200 即可
HTTPS_PROXY="http://127.0.0.1:<port>" HTTP_PROXY="http://127.0.0.1:<port>" git push
```

端口每次会变（见过 7890 / 4385 / 14433 …），以实际监听为准；`SOCKS5` 兜底写法见用户级 MEMORY.md。

## 关键实现

### 主界面控制面板

列表每一项现在是一张可操作卡片：标题行（图标 + 标题 + 状态 + 删除）下面依次是
「透明度滑块 / 可见性·锁定两个开关 / 缩放滑块 + 重置按钮」。
改动经 `persistAndSync()` 写回 Store，再 `OverlayService.refresh()` 同步到浮窗；
反向（浮窗上操作）仍走 `MainActivity.onStoreChanged()` 刷新列表。

**主界面在前台时浮窗自动隐藏**（`INVISIBLE`，保留位置），离开（onPause）后恢复——
否则大浮窗会盖住列表控件，点不到。见 `OverlayService.onActivityForeground()`。

### 数据流

`Store`（SharedPreferences）是唯一数据源。Activity 改动后调 `OverlayService.start() + refresh()`；Service 的 `sync()` 对比 Holder 与 Store，做三件事：删除消失的、新增缺的、内容变化的重建。浮窗上的操作（显隐 / 锁定 / 透明度）直接写回 Store。

### 浮窗视图结构

```
WindowRoot (自定义 LinearLayout，拦截双指手势，padding 6dp)
└── card  ← 唯一卡片，圆角 18dp + clipToOutline，拖动监听挂这里
    ├── row      : [眼睛][锁][重置][叉] + SeekBar(weight) + 百分比   一行
    ├── dragBar  : 18dp 高的触摸区，中间一条 44×3dp 圆角短线当把手
    └── bodyBox  : 隐藏时整体 GONE，透明度作用于它
        └── 内容：MaxScroll+TextView（最大高 46% 屏高）/ MaxImageView
```

控制行全部挤在一行：四个按钮各 32dp、滑块 `weight=1`、百分比固定 30dp。
卡片宽度 < 204dp 时自动隐藏百分比，把空间让给滑块。

透明度实现：**卡片背景色的 alpha + bodyBox 的 alpha 同步变化，控制行不参与**。
这样调低透明度时卡片能透出背后的画面，但按钮和滑块始终清晰、可操作。
`buildCardBg()` 里底色 alpha 下限 0.12，避免整张卡完全消失。

缩放：`scale` 字段（**0.1–8**）驱动字号（15sp × scale）；宽度则由 `computeCardWidth()` 按内容算：

```
文字 → 用 TextPaint 量出最长一行的宽度 + 内边距
图片 → 46% 屏高 × 原始宽高比
结果 × scale，再夹在 [下限, 上限]：
  下限 = 180dp（scale > 1 时变成 180 × scale）  ← 保证四个按钮放得下
  上限 = 320dp × max(1, scale)，且不超过屏宽 - 20dp
```

所以短文本的卡片就是窄的，长文本会摊开；放大时短卡片也会等比变宽（下限跟着 scale 走）。
**重置按钮**（`ic_fit`）把 scale 打回 1.0，位置随之重新计算。

位置自适应：`keepInsideScreen()` 在布局完成后按卡片实际高度把 `y` 往上顶，避免长文本撑出屏幕底部。

内容最大高度由 `computeMaxBodyH(scale)` 决定：`scale ≤ 1` 时是 46% 屏高；`scale > 1` 时按比例放开、上限「屏高 - 160dp」。这样放大时宽高一起长，不会只变宽。

### 踩坑记录（改代码前必读）

1. **子 View 的 LayoutParams 必须在 `addView(child, params)` 时传入**。
   先 `child.setLayoutParams(lp)` 再 `root.addView(child)` 会导致该子 View 布局成 `0×0`（本项目的控制卡片就因此整块消失）。
2. `MaxScroll` / `MaxImageView` 必须重写 `onMeasure` 限制最大高度，否则长文本 / 竖图会撑出屏幕。
3. 拖动监听挂在内层控制卡片上（`setOnTouchListener`）。子 View（图标、SeekBar）会先消费事件，只有卡片空白区域能触发拖动——这是预期行为，不要给卡片加 `clickable`。
4. 锁定 = 只锁位置（禁止拖动、禁止 App 内编辑），控制按钮仍可点，否则无法解锁。
5. 浮窗 `LayoutParams` 不加 `FLAG_LAYOUT_NO_LIMITS`，让系统自动把窗口夹在屏幕内；另外对 `x/y` 做上限钳制，防止旋转 / 分辨率变化后跑出屏幕。
6. **`ScaleGestureDetector.getInitialSpan()` 不是 public**，编译期报「找不到符号」。缩放要用 `getScaleFactor()` 相对上一次累乘（`h.item.scale * d.getScaleFactor()`），或在 `onScaleBegin` 自己记初值后结合 `getCurrentSpan()`。
7. 双指手势在 `WindowRoot.dispatchTouchEvent` 里拦截：事件先喂给 `ScaleGestureDetector`，一旦 `pointerCount > 1` 就给子 View 补一个 `ACTION_CANCEL`（清掉按钮按压态）并 `return true` 接管，抬手时落库。
8. `build.sh` 里 javac 曾用 `| grep -v ... || true` 把编译错误吞掉，导致 d8 打出一个缺类的 APK、装上就 `ClassNotFoundException`。现在 javac 失败会直接中止构建并打印日志。

### 权限

- `SYSTEM_ALERT_WINDOW`：浮窗必需。无权限时不启动服务，界面上显示引导横幅，按钮跳 `ACTION_MANAGE_OVERLAY_PERMISSION`。
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`（API 34 要求声明类型，并带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。
- **MIUI 注意**：`adb install -r` 重新安装后 `appops SYSTEM_ALERT_WINDOW` 会被重置为 `ignore`，需重新授权（用户侧表现为「权限不知为何又关了」）。手动测试时用
  `adb shell appops set com.elowyyn.floatnotes SYSTEM_ALERT_WINDOW allow`。

### 图片处理

相册选择（`ACTION_OPEN_DOCUMENT`，支持多选）→ 二次采样 → 最长边压到 1280 → JPEG q85 存 `filesDir/overlays/{id}.jpg` → 模型只存路径。删除浮窗时同步删文件。

## 测试设备

小米平板（2560×1600，横屏，density 347）。横屏下列表限宽 640dp 居中，FAB 固定右下；竖屏手机同样适用（内容宽度自适应）。

## 待办

- 浮窗内点击文字直接跳编辑（目前需回 App）
- 导出 / 导入 JSON 备份
- 服务被系统杀掉后的自动恢复（当前依赖 START_STICKY + 重进 App）
- 删除 / 清空的确认框仍是系统 `AlertDialog`，与整体 MD3 风格不一致，可换成自绘圆角 Dialog

## 变更记录

| 版本 | 内容 |
| --- | --- |
| 1.0 | 首个可用版本：文字 / 图片浮窗、显隐、锁定、透明度、拖动、持久化、前台服务 |
| 1.1 | 控制栏与内容合并为单张卡片（含分隔线）；双指捏合缩放（宽度 + 字号）；新浮窗阶梯错位避让；透明度改为「卡片底色 + 内容」联动，控制栏保持清晰 |
| 1.2 | 缩放范围放宽到 0.1–8×；内容可缩到极小但卡片最小保 140dp（按钮始终可点） |
| 1.3 | 四个按钮 + 滑块合并成一行；新增「重置缩放」按钮；卡片宽度按文字/图片内容自适应；长内容撑出屏幕时自动上移；拖动入口改为控制行下的短横把手 |
| 1.4 | 透明度滑块从控制行移到独立子窗口（固定 244dp 宽，点 ◐ 按钮开合，贴卡片下方、放不下翻到上方，随卡片拖动/缩放联动） |
| 1.5 | 主界面列表项升级为完整控制面板：透明度滑块、可见性开关、锁定开关、缩放滑块、重置按钮，改动实时同步到浮窗；主界面在前台时浮窗自动隐藏，离开后恢复 |
| 1.6 | 双指缩放改为整体等比放大：内容最大高度随 scale 放开（原来固定 46% 屏高，放大时只变宽不变高，看着像「只放大一边」） |
