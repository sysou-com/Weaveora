-- ============================================================
-- V17：我的剧本（剧本 / 集 / 变更记录 / 剧本集市点赞收藏）
-- 约定沿用 V1：UUIDv7 PK（应用生成）、timestamptz UTC、软删 deleted_at、业务表带 workspace_id。
-- 集市（剧本精选）：share_status = pending | approved | rejected（null=未分享），镜像 projects 的集市口径。
-- ============================================================

CREATE TABLE scripts (
  id               uuid PRIMARY KEY,
  workspace_id     uuid NOT NULL REFERENCES workspaces(id),
  created_by       uuid NOT NULL REFERENCES users(id),
  title            text NOT NULL,
  genre            text NOT NULL DEFAULT '',       -- 剧本类型
  characters       text NOT NULL DEFAULT '',       -- 剧本人物及人物介绍
  story            text NOT NULL DEFAULT '',       -- 剧本故事
  conflict         text NOT NULL DEFAULT '',       -- 剧本冲突
  plot_structure   text NOT NULL DEFAULT '',       -- 剧本情节结构
  language         text NOT NULL DEFAULT '',       -- 剧本语言
  stage_directions text NOT NULL DEFAULT '',       -- 舞台说明
  condensed_story  text NOT NULL DEFAULT '',       -- AI 连续记忆：精简的故事
  status           text NOT NULL DEFAULT 'draft',  -- draft | writing | completed
  share_status     text,                           -- null=未分享 | pending | approved | rejected
  shared_at        timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);

CREATE INDEX idx_scripts_ws ON scripts(workspace_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_scripts_share ON scripts(share_status, updated_at DESC)
    WHERE share_status = 'approved' AND deleted_at IS NULL;

CREATE TABLE script_episodes (
  id           uuid PRIMARY KEY,
  script_id    uuid NOT NULL REFERENCES scripts(id),
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  episode_no   int  NOT NULL,
  title        text NOT NULL DEFAULT '',
  content      text NOT NULL DEFAULT '',
  summary      text NOT NULL DEFAULT '',           -- 本集摘要（供精简故事/后续生成）
  ai_polished  boolean NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_script_episode_no UNIQUE (script_id, episode_no)
);

CREATE INDEX idx_script_episodes_script ON script_episodes(script_id, episode_no);

-- 「记录之前章节哪些地方有改动」：每次保存集 / 应用一致性改动都落一行
CREATE TABLE script_changes (
  id               uuid PRIMARY KEY,
  script_id        uuid NOT NULL REFERENCES scripts(id),
  workspace_id     uuid NOT NULL REFERENCES workspaces(id),
  episode_id       uuid,                           -- 触发者（可空=要素/精简故事更新）
  episode_no       int,
  kind             text NOT NULL,                  -- episode_create|episode_update|consistency_proposal|consistency_applied|condensed_refresh|field_update
  changed_episodes jsonb NOT NULL DEFAULT '[]',    -- [{episodeNo,title,what}] 受影响的既有章节
  note             text NOT NULL DEFAULT '',
  actor            text NOT NULL DEFAULT 'user',   -- user | ai
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_script_changes_script ON script_changes(script_id, created_at DESC);

-- 剧本集市点赞/收藏（镜像 project_marks：一用户×剧本×kind 一行）
CREATE TABLE script_marks (
  script_id   uuid NOT NULL REFERENCES scripts(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
  kind        text NOT NULL CHECK (kind IN ('like', 'fav')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (script_id, user_id, kind)
);

CREATE INDEX idx_script_marks_kind ON script_marks(script_id, kind);

-- 集 / 变更活动 → touch 所属剧本 updated_at（“最近更新”排序口径与项目一致）
CREATE OR REPLACE FUNCTION weaveora_touch_script(sid uuid) RETURNS void AS $$
BEGIN
    UPDATE scripts SET updated_at = now()
     WHERE id = sid AND deleted_at IS NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_touch_script_episodes() RETURNS trigger AS $$
BEGIN
    PERFORM weaveora_touch_script(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.script_id ELSE NEW.script_id END);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_touch_script_changes() RETURNS trigger AS $$
BEGIN
    PERFORM weaveora_touch_script(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.script_id ELSE NEW.script_id END);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_script_touch_episodes
    AFTER INSERT OR UPDATE OR DELETE ON script_episodes
    FOR EACH ROW EXECUTE FUNCTION trg_touch_script_episodes();
CREATE TRIGGER trg_script_touch_changes
    AFTER INSERT OR UPDATE OR DELETE ON script_changes
    FOR EACH ROW EXECUTE FUNCTION trg_touch_script_changes();
