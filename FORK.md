# Podara 二次开发说明（haha-anything fork）

本仓库是 [kazimics/podara](https://github.com/kazimics/podara) 的个人分支，基线版本 `1.0.2`（commit `a158d57`）。

## 与原版的差异

| # | 改动 | 相关提交 |
|---|------|----------|
| 1 | 订阅页改为「最新更新」聚合视图：上方横向频道条（可滑动、有未播放圆点），下方跨订阅的纵向最新节目列表 | `feat(subscriptions): 订阅页改为「最新更新」聚合视图` |
| 2 | 播放列表只通过「加入队列」手动增长，点单集不再把整个频道的节目灌进队列 | `feat(player): 播放列表改为手动添加，不再自动塞入整档节目` |
| 3 | 支持系统媒体键（播放/暂停、上一首、下一首、停止、音量加/减/静音），应用在后台同样生效 | `fix(player): 支持系统媒体键…` |

### 1. 订阅页（`App.kt` / `HomeScreen`）

- 频道以 `LazyRow` 横向平铺，鼠标滚轮/拖拽左右滑动；封面下方的小圆点是未播放提示。
- 纵向列表由 `podcastEpisode.new = 1` 驱动，按 `pubDate` 倒序，上限 150 条，可「只看未播放」。
- 进入页面时按 10 分钟节流并发刷新（并发 3）所有订阅，获取新节目；设置里可关闭。
- 首次抓取一个频道时作为基线，不会把所有历史节目标成未播放。

### 2. 播放队列（`App.kt` / `PodcastDetailScreen`）

点节目 = `MediaPlayerState.play()`，只追加当前条目。History / Favorites / Downloads
三处的 `playWithContext` 保持原样（那些是用户自己整理过的列表）。

### 3. 收听进度（`player/EpisodeProgress.kt`、`component/EpisodeProgressUi.kt`）

原版有 `podcastEpisodePlayState` 表，但 `state/played` 从来没人写，等于没有进度。

- 新增 `positionMs / durationMs / finished` 三列（`ALTER TABLE` 幂等迁移，老库直接升级）。
- `MediaPlayerState.onProgressUpdate` 每秒上报一次位置；切集、暂停、停止时立即 flush。
  `EpisodeProgressStore` 落库节流 5 秒/集，听完的那一刻立即写。
- **进度 > 98% 记为听完**（`EPISODE_FINISHED_THRESHOLD`），`finished` 是粘性的：
  重听不会把「已听完」打回未完成。
- 展示形态：卡片底部 3dp 横向进度条 + 元信息行的状态标签
  （`▶ 已听 45%` / `▶ 在听 8%` / `✓ 已听完`）。没选圆环是因为列表密度下
  72dp 封面上的圆环几乎看不清。
- 三处共用同一个 `EpisodeListItem`：最新更新、频道详情、播放队列（队列卡片单独实现，样式一致）。
- 百分比向下取整、未听完封顶 97%，避免「98% 但没听完」的歧义。

**联动**：节目一旦播放过（或有进度）或进入播放队列，就从「最新更新」聚合列表里移除
（`EpisodeProgressStore.isHiddenFromLatest`），只在播放列表里出现；全部移除后空状态
显示「新节目都已进入播放列表」。

### 4. 媒体键（`util/WindowsMediaKeys.kt`、`player/MediaKeyController.kt`）

Windows 把媒体键投递给前台窗口，Compose/AWT 不会生成 `KeyEvent`，所以必须用
`WH_KEYBOARD_LL` 低级键盘钩子。钩子装在独立线程上并跑自己的 `GetMessage` 循环；
命中媒体键后返回 1 吞掉事件（和其它桌面播放器一致）。

实现时踩到的坑，改动前请先读：

- `WPARAM` 是按指针宽度传递的**整数**。必须 `Pointer.nativeValue(wParam)` 取数值，
  用 `getInt(0)` 会把它当地址解引用 → 回调内抛异常、事件被静默丢弃。
- `KBDLLHOOKSTRUCT.vkCode` 是 `lParam` 指向结构的第 0 个 `DWORD`，`lParam.getInt(0)` 才对。
- 音量键只在已载入节目时拦截，否则空闲时会把系统音量键抢走。
- 回调会被长按重复触发，播放/暂停/静音/停止做了 350ms 去抖。

## 日常启动（唯一入口，双击即可）

`启动 Podara.vbs` —— 仓库根目录一份，桌面上也放了一份 `Podara 播客.vbs`。
双击它就行，不需要开终端，也**不用记「什么时候该先编译」**。

它自己会判断：

| 情况 | 行为 | 耗时 |
|------|------|------|
| 代码没改过 | 直接 `javaw` 启动 | 2~3 秒 |
| 源码 / 构建配置改过 | 弹出编译窗口跑 Gradle，成功后自动启动 | 10~30 秒 |
| 编译失败 | 窗口停住显示报错，并弹提示框，不启动 | — |

### 为什么拆成 vbs + ps1 两个文件

`启动 Podara.vbs` 只有 11 行，**只做一件事**：用 `powershell -File` 调
`启动 Podara.ps1`，真正的逻辑全在 ps1 里。

这么拆是因为 **VBScript 没有任何办法在交付前做语法校验** ——
第一版把逻辑写进 vbs，结果 `Dim folder, f, sub, ...` 里的 `sub` 是 VBScript
保留字（`Sub` 过程声明），编译期报 `800A03F2 缺少标识符`，双击直接弹错误框。
而 PowerShell 可以用 `[System.Management.Automation.PSParser]::Tokenize()`
做真正的语法解析，逻辑放这边就能在交付前跑通验证。

改逻辑请改 **`.ps1`**，不要去动 `.vbs`。

### 判断依据

`desktop/build/.last-successful-build` 这个时间戳：拿 `desktop/src/desktopMain`、
`desktop/src/commonMain` 和 6 个构建配置文件的修改时间跟它比，谁更新就重编译。
**不要用 class 文件的修改时间去比** —— Gradle 判定 UP-TO-DATE 时根本不重写 class，
那样会导致每次双击都编译一遍。

启动时实际执行的就是：

```
javaw -Dfile.encoding=UTF-8 --add-modules=java.sql \
      -cp "<classes>;<processedResources>;<maincp.txt 里的 79 个 jar>" \
      app.podara.MainKt
```

`desktop/build/maincp.txt` 是完整运行时 classpath，由
`scripts/export-classpath.init.gradle` 导出（每次编译都会顺带刷新一次，所以加了新依赖也不用管）。

### 踩过的坑

- **`.bat` 必须用 CRLF 换行。** 之前给的 `启动开发版.bat` 是 LF 换行，
  Windows 双击它毫无反应 —— cmd 对纯 LF 的批处理解析会出问题，且不报错。已删除该文件。
  现在 ps1 需要现场生成 bat 时，一律用 `"`r`n"` 拼并且按 GBK 存盘。
- **`sub` 是 VBScript 保留字**，不能当变量名（见上）。
- VBS 文件必须存成 **UTF-16LE + BOM**；`.ps1` 必须存成 **UTF-8 with BOM**
  （PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI 读，中文全乱）。
- 启动 `javaw` 的窗口样式要用 `1`（正常）不要用 `0`（隐藏），
  隐藏样式有机会把 GUI 主窗口一起藏掉，表现就是「点了没反应」。
- `.vbs` 和 `.ps1` 里都有绝对路径常量（`ps1` 路径、`$DefaultProjectDir`），
  项目搬家了要各改一处。

用户数据（数据库、设置、下载）都在 `%USERPROFILE%\.podara\`，与启动时的工作目录无关，
所以双击启动和 Gradle 启动看到的是同一份数据。

## 构建

需要 JDK 17+（JRE 不够，`jlink`/`javac` 都要用到）。

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :desktop:run            # 直接运行
./gradlew :desktop:packageMsi     # 打 MSI
```

### 打包成安装包目前不可行

`./gradlew :desktop:createDistributable` 会失败在两个点上，别浪费时间重试：

1. `:createRuntimeImage` → `jlink` 报「未指定 --module-path」。本机只有 Podara 自带的
   runtime（`D:\Program Files\Podara\runtime`），它是删掉 `jmods/` 的精简 JDK，
   jlink 没有模块可读。要么装一个完整 JDK，要么手动下载 `jmods`。
2. `:downloadWix` → 连不上 `github.com:443`（本机到 GitHub 的直连超时），
   WiX 下载不下来就没法出 MSI。

结论：本机用便携启动器即可；要出安装包需要完整 JDK + 能访问 GitHub/WiX。

## 测试

已知问题：`./gradlew :desktop:desktopTest` 在这份工程里会 22 个 class 全部
`ClassNotFoundException`（KMP `jvm("desktop")` 下 `testClassesDirs` 指向了空目录，
与本次改动无关）。绕过方式是把测试类目录指过去后直接用 JUnit 跑：

```bash
./gradlew :desktop:compileTestKotlinDesktop
CP="desktop/build/classes/kotlin/desktop/test;$(cat desktop/build/testcp.txt)"
java -cp "$CP" org.junit.runner.JUnitCore app.podara.desktop.UpdatePodcastTest
```

`EpisodeProgressTest` / `EpisodeProgressUiTest` 覆盖 98% 判定、进度落库、列表与队列渲染。

`MediaKeyHookTest` 需要交互式桌面会话（会真实安装键盘钩子并注入一次
`VK_MEDIA_PLAY_PAUSE`），无桌面环境时自动跳过。

## 与上游同步

```bash
git fetch upstream
git rebase upstream/main          # 或 merge
```

`upstream` 指向 `kazimics/podara`，`origin` 指向本 fork。
