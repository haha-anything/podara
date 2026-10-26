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

## 构建

需要 JDK 17+（JRE 不够，`jlink`/`javac` 都要用到）。

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :desktop:run            # 直接运行
./gradlew :desktop:packageMsi     # 打 MSI
```

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
