-- 001_init.sql — 大宇宙播客 初始 schema
-- 用法：拷到 src-tauri/migrations/001_init.sql，由 core/db/mod.rs 按序执行
-- 注意：每个连接都要先执行 PRAGMA foreign_keys = ON（SQLite 默认关闭）

PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

CREATE TABLE IF NOT EXISTS schema_version (
  version     INTEGER PRIMARY KEY,
  applied_at  INTEGER NOT NULL
);

-- ── 播客（一条记录 = 一个订阅，订阅关系就是这个表）────────────────────
CREATE TABLE IF NOT EXISTS podcast (
  origin          TEXT PRIMARY KEY,          -- RSS URL，天然去重
  title           TEXT NOT NULL,
  custom_title    TEXT NOT NULL DEFAULT '',  -- 用户手动改名，非空时优先展示
  author          TEXT,
  description     TEXT,
  image_url       TEXT,
  link            TEXT,
  language        TEXT,
  seed_color      INTEGER NOT NULL DEFAULT 0, -- 封面主色，0 表示未取
  source          TEXT NOT NULL DEFAULT 'rss', -- rss | apple | podcastindex（仅记录来路）
  skip_intro_sec  INTEGER NOT NULL DEFAULT 0,
  skip_outro_sec  INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL
);

-- ── 单集 ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS episode (
  id             TEXT PRIMARY KEY,           -- {feedUrl}:{episodeUrl}
  guid           TEXT,                       -- RSS 原始 guid，刷新去重依据
  origin         TEXT NOT NULL REFERENCES podcast(origin) ON DELETE CASCADE,
  title          TEXT NOT NULL,
  description    TEXT,
  link           TEXT,
  author         TEXT,
  audio_url      TEXT NOT NULL,
  image_url      TEXT,
  pub_date       INTEGER NOT NULL,
  duration_ms    INTEGER NOT NULL DEFAULT 0, -- 毫秒！RSS 解析出秒后 ×1000
  is_new         INTEGER NOT NULL DEFAULT 0,
  chapters_url   TEXT,
  transcript_url TEXT
);
CREATE INDEX IF NOT EXISTS idx_episode_origin_pub ON episode(origin, pub_date DESC);
CREATE INDEX IF NOT EXISTS idx_episode_guid       ON episode(origin, guid);
CREATE INDEX IF NOT EXISTS idx_episode_new        ON episode(is_new) WHERE is_new = 1;
CREATE INDEX IF NOT EXISTS idx_episode_audio      ON episode(audio_url);

-- ── 播放进度 ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS play_state (
  episode_id    TEXT PRIMARY KEY REFERENCES episode(id) ON DELETE CASCADE,
  position_ms   INTEGER NOT NULL DEFAULT 0,
  duration_ms   INTEGER NOT NULL DEFAULT 0,
  finished      INTEGER NOT NULL DEFAULT 0,  -- 粘性：>98% 置 1，永不清除
  updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_play_state_updated ON play_state(updated_at DESC);

-- ── 收藏 / 历史（独立表：需要独立时间排序，且要扛住刷新清理）────────
CREATE TABLE IF NOT EXISTS favorite (
  episode_id  TEXT PRIMARY KEY REFERENCES episode(id) ON DELETE CASCADE,
  added_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_favorite_added ON favorite(added_at DESC);

CREATE TABLE IF NOT EXISTS history (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  episode_id  TEXT NOT NULL,
  origin      TEXT NOT NULL,
  played_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_history_time ON history(played_at DESC);

-- ── 订阅的可选配置 + 增量抓取缓存 ────────────────────────────────────
CREATE TABLE IF NOT EXISTS subscription (
  origin         TEXT PRIMARY KEY REFERENCES podcast(origin) ON DELETE CASCADE,
  notify_new     INTEGER NOT NULL DEFAULT 1,
  auto_download  INTEGER NOT NULL DEFAULT 0,
  etag           TEXT NOT NULL DEFAULT '',
  last_modified  TEXT NOT NULL DEFAULT '',
  content_length TEXT NOT NULL DEFAULT '',
  last_checked   INTEGER NOT NULL DEFAULT 0,
  new_count      INTEGER NOT NULL DEFAULT 0
);

-- ── 下载任务（完成态与进行态同一张表，靠 status 区分）────────────────
CREATE TABLE IF NOT EXISTS download_task (
  episode_id  TEXT PRIMARY KEY REFERENCES episode(id) ON DELETE CASCADE,
  origin      TEXT NOT NULL,
  file_name   TEXT,                          -- 只存文件名，不存绝对路径
  received    INTEGER NOT NULL DEFAULT 0,
  total       INTEGER NOT NULL DEFAULT 0,
  status      TEXT NOT NULL DEFAULT 'pending', -- pending|downloading|paused|done|failed
  error       TEXT,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_download_status ON download_task(status);

-- ── 播放会话（重启恢复，单行表）─────────────────────────────────────
CREATE TABLE IF NOT EXISTS player_session (
  id              INTEGER PRIMARY KEY CHECK (id = 1),
  current_episode TEXT,
  position_ms     INTEGER NOT NULL DEFAULT 0,
  speed           REAL    NOT NULL DEFAULT 1.0,
  volume          INTEGER NOT NULL DEFAULT 100,
  updated_at      INTEGER NOT NULL DEFAULT 0
);

-- ── 播放队列（顺序即 id 顺序；episode_id 可空以支持裸 URL）───────────
CREATE TABLE IF NOT EXISTS queue_item (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  episode_id  TEXT REFERENCES episode(id) ON DELETE CASCADE,
  raw_url     TEXT,
  title       TEXT,
  added_at    INTEGER NOT NULL,
  CHECK (episode_id IS NOT NULL OR raw_url IS NOT NULL)
);

INSERT OR REPLACE INTO schema_version (version, applied_at)
VALUES (1, CAST(strftime('%s','now') AS INTEGER) * 1000);
