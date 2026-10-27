# 大宇宙播客 · Dear Universe Podcast

一个 Windows 优先、开源、轻量的桌面播客客户端。

Tauri 2 + Svelte 5 + Rust + SQLite。冷启动 <1.5s，空闲内存 <120MB，安装包 <15MB。

> 状态：**M0 开发中**。功能清单见 `docs/开发任务书.md`。

---

## 它是什么

一个**通用 RSS 播客客户端**。你给它一个 RSS 地址，它就能订阅、播放、记进度。

- 核心能力（订阅 / 播放 / 进度 / 下载）**不依赖任何第三方服务**——
  把网线拔了再插回来、把发现页的目录 API 拉黑，应用照样能用。
- 发现页会用 Apple Podcasts 的公开接口帮你找节目（只取元数据，音频永远从节目自己的
  RSS 拉），但**它挂掉不影响你听已有的订阅**。
- 支持 OPML 导入导出，随时能搬到别的客户端，也随时能搬回来。

## 它不是什么

不是推荐 App，不是社区，不是内容平台。

没有推荐信息流（没数据，硬做只会难看）、没有评论、没有赞赏、不做内容转存。
这类产品已经够多了，缺的是**一个安静、快、不打扰的播放器**。

## 功能

| 模块 | 内容 |
|------|------|
| 订阅 | 粘贴 RSS、OPML 导入导出、并发刷新、增量抓取（ETag） |
| 播放 | 队列、倍速 0.5–3.0、跳过片头片尾、音量增强、睡眠定时、章节跳转 |
| 进度 | 跨播客「最新更新」聚合流、98% 完播判定、重启接着播 |
| 资料库 | 历史、收藏、下载（断点续传 + 限速） |
| 系统 | 托盘、媒体键、新集通知、单实例、自动更新 |

## 开发

### 环境

需要 Rust（≥1.80）、Node 22、Visual Studio C++ 生成工具、WebView2。
完整步骤见 `docs/开发任务书.md` §2。

### 跑起来

```bash
pnpm install
pnpm tauri dev
```

### 打包

```bash
pnpm tauri build
# 产物：src-tauri/target/release/bundle/nsis/
```

### 目录

```
src-tauri/          Rust 后端
  src/commands/     IPC 命令（只做校验和转发）
  src/core/         业务逻辑
    db/             所有 SQL 都在这
    sources/        RSS 抓取 + Apple 目录
  migrations/       按序执行的 SQL
src/                Svelte 前端
  routes/           六个页面
  lib/api/          invoke 封装（组件不直接调 invoke）
  lib/stores/       播放器与队列状态
```

## 给 AI 协作者

读 `AGENTS.md`。里面有硬规则：不用 ORM、不用 UI 库、不抓非官方接口、
不写巨型文件、每个里程碑的完成定义。

## 许可证

GPL-3.0。本项目参考了 [podara](https://github.com/kazimics/podara)（同样 GPL-3.0）
的产品设计与实现思路，按衍生作品处理。
