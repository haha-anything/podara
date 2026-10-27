# AGENTS.md — 大宇宙播客 / Dear Universe Podcast

> 这个文件是给 AI 常驻读的**硬规则**。完整规格看 `docs/开发任务书.md`。
> 规则冲突时，本文件优先。

## 项目标识

| 项 | 值 |
|----|-----|
| 英文名 | `dear-universe-podcast` |
| 中文名 | 大宇宙播客 |
| Tauri identifier | `com.dearuniverse.podcast` |
| 二进制名 | `dear-universe-podcast` |
| 数据目录 | `%APPDATA%\com.dearuniverse.podcast\`（macOS: `~/Library/Application Support/com.dearuniverse.podcast/`） |
| 数据库文件 | `dear-universe.db` |
| 许可证 | GPL-3.0 |

**改名代价极高**：identifier 一改，用户数据目录就变了，等于丢库。不要再动。

## 技术栈（已锁定，不许讨论）

```
壳      Tauri 2（系统 WebView2，不自带浏览器）
前端    Svelte 5（runes）+ TypeScript + Vite
样式    Tailwind CSS 4
后端    Rust（stable，≥1.80）
DB      SQLite + rusqlite（bundled feature）
HTTP    reqwest（rustls-tls，不用 openssl）
RSS     feed-rs
异步    tokio
日志    tracing + tracing-subscriber
```

## 绝对不能做的事

1. **不引入 ORM**（diesel / sea-orm / sqlx 的 migrate）。手写 SQL，`rusqlite` 直接用。
2. **不引入 UI 组件库**（MUI / Ant / shadcn / Skeleton）。Tailwind 手写。
3. **不引入状态管理库**（Redux / Zustand / MobX）。Svelte 5 runes 够用。
4. **不引入动画库**（Framer Motion / GSAP）。Svelte 内置 `transition` / `animate`。
5. **不抓任何非官方接口**。不爬网页、不逆向 API、不模拟登录。
   只允许调用 Apple 的四个公开端点（见任务书 §6）。
6. **前端不许碰数据库**。所有 SQL 只在 Rust 侧。
7. **不用 CSS 阴影做层次**。只用 1px 描边 + 背景色差。
8. **不在 <audio> 之外自研音频解码**。V1 不做 FFI 音频后端。
9. **不把逻辑塞进单个巨型文件**。单文件超过 400 行就该拆
   （参考实现有个 2476 行的 `App.kt`，不要重演）。
10. **不写"看起来能用但没接上"的死代码**。要么接通，要么不写。
    （参考实现留了 `enableNotifications`、`Genre` 枚举、`SyncManager` 三个半成品，作为反例记住。）

## 代码规范

### Rust

- 分层：`commands/` 只做参数校验和转发，业务在 `core/`，DB 访问只在 `core/db/`
- 错误：库层 `thiserror`，应用层 `anyhow`；命令返回值必须是 `Result<T, String>`
- 所有 `invoke` 命令都要能返回结构化错误，不许 `unwrap()` 到崩溃
- SQL 一律用 `params![]` 绑定参数，不许字符串拼接
- 每个 DB 连接都要 `PRAGMA foreign_keys = ON`

### TypeScript / Svelte

- 严格模式，不用 `any`（确实需要时用 `unknown` + 类型收窄）
- `invoke` 统一走 `src/lib/api/`，组件里不直接调 `invoke`
- 状态用 runes：`$state` / `$derived` / `$effect`，不用 store 包一层
- 组件文件名 PascalCase，工具函数 camelCase
- 任何列表渲染超过 200 条，必须虚拟滚动

### 提交

```
<type>(<scope>): <中文描述>

type: feat | fix | refactor | perf | docs | chore | test
scope: feed | player | db | ui | download | platform | deps
```

一次提交只做一件事。**改完必须 `cargo check` + `pnpm check` 都过才能提交。**

## 每个里程碑的完成定义（DoD）

1. `cargo check` 无错误、无 warning（允许 known warning 但要在注释里说明）
2. `pnpm check` 无类型错误
3. 应用能 `pnpm tauri dev` 起得来，功能手动验证通过
4. 该里程碑的验收标准（任务书 §7）逐条勾掉
5. 更新的文档已提交

## 交互偏好

- 中文回复，代码注释用中文，标识符用英文
- 不确定的技术决策：**先给 2–3 个方案和取舍，问一句再动手**，不要自作主张改架构
- 报错时先给根因再给修法，不要贴一大段日志了事
- 涉及删数据的操作，先 dry-run 输出统计，确认后再执行
