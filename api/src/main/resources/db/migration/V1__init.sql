-- ============================================================
-- Weaveora V1__init.sql — 基础表（镜像 Weaveora.md §14）
-- 约定：UUIDv7 PK、timestamptz UTC、软删 deleted_at、业务表带 workspace_id
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";

CREATE TABLE users (
  id            uuid PRIMARY KEY,
  email         citext UNIQUE,
  phone         text UNIQUE,
  password_hash text,
  display_name  text NOT NULL,
  status        text NOT NULL DEFAULT 'active',
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);

CREATE TABLE workspaces (
  id            uuid PRIMARY KEY,
  name          text NOT NULL,
  owner_user_id uuid NOT NULL,
  plan          text NOT NULL DEFAULT 'free',
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);

CREATE TABLE memberships (
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  user_id       uuid NOT NULL REFERENCES users(id),
  role          text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE credit_wallets (
  workspace_id  uuid PRIMARY KEY REFERENCES workspaces(id),
  balance       numeric(12,4) NOT NULL DEFAULT 0,
  frozen        numeric(12,4) NOT NULL DEFAULT 0,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_wallet_nonneg CHECK (balance >= 0 AND frozen >= 0)
);

CREATE TABLE style_templates (
  id            uuid PRIMARY KEY,
  workspace_id  uuid REFERENCES workspaces(id),
  slug          text NOT NULL,
  name          text NOT NULL,
  prompt_prefix text NOT NULL DEFAULT '',
  prompt_suffix text NOT NULL DEFAULT '',
  negative      text NOT NULL DEFAULT '',
  default_params jsonb NOT NULL DEFAULT '{}',
  is_system     boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE model_presets (
  id            uuid PRIMARY KEY,
  slug          text NOT NULL UNIQUE,
  kind          text NOT NULL,
  workflow_id   text NOT NULL,
  display_name  text NOT NULL,
  cost_unit     text NOT NULL,
  cost_credits  numeric(12,4) NOT NULL,
  enabled       boolean NOT NULL DEFAULT true
);

CREATE TABLE projects (
  id                    uuid PRIMARY KEY,
  workspace_id          uuid NOT NULL REFERENCES workspaces(id),
  created_by            uuid NOT NULL REFERENCES users(id),
  title                 text NOT NULL,
  mode                  text NOT NULL,
  aspect_ratio          text NOT NULL,
  duration_sec          numeric(6,2),
  style_template_id     uuid REFERENCES style_templates(id),
  status                text NOT NULL,
  approved_revision_id  uuid,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  deleted_at            timestamptz
);

CREATE TABLE briefs (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES projects(id),
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  raw_text      text NOT NULL,
  mode          text NOT NULL,
  constraints   jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prompt_revisions (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES projects(id),
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  brief_id      uuid NOT NULL REFERENCES briefs(id),
  revision_no   int NOT NULL,
  source        text NOT NULL,
  schema_json   jsonb NOT NULL,
  title         text,
  logline       text,
  positive_prompt text,
  negative_prompt text,
  created_by    uuid REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (project_id, revision_no)
);

CREATE TABLE shot_drafts (
  id            uuid PRIMARY KEY,
  revision_id   uuid NOT NULL REFERENCES prompt_revisions(id) ON DELETE CASCADE,
  shot_no       int NOT NULL,
  duration_sec  numeric(6,2) NOT NULL,
  shot_size     text,
  camera_move   text,
  action        text,
  positive_prompt text NOT NULL,
  negative_prompt text NOT NULL,
  seed_lock     boolean NOT NULL DEFAULT true,
  ref_shot_no   int,
  status        text NOT NULL DEFAULT 'draft',
  UNIQUE (revision_id, shot_no)
);

CREATE TABLE generation_jobs (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES projects(id),
  workspace_id    uuid NOT NULL REFERENCES workspaces(id),
  revision_id     uuid REFERENCES prompt_revisions(id),
  shot_id         uuid REFERENCES shot_drafts(id),
  model_preset_id uuid NOT NULL REFERENCES model_presets(id),
  kind            text NOT NULL,
  state           text NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  payload         jsonb NOT NULL,
  progress        int NOT NULL DEFAULT 0,
  stage           text,
  error_code      text,
  error_message   text,
  credits_reserved numeric(12,4) NOT NULL DEFAULT 0,
  credits_settled  numeric(12,4) NOT NULL DEFAULT 0,
  worker_id       text,
  created_by      uuid NOT NULL REFERENCES users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  started_at      timestamptz,
  finished_at     timestamptz
);

CREATE TABLE assets (
  id            uuid PRIMARY KEY,
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  project_id    uuid NOT NULL REFERENCES projects(id),
  job_id        uuid REFERENCES generation_jobs(id),
  shot_id       uuid REFERENCES shot_drafts(id),
  kind          text NOT NULL,
  storage_key   text NOT NULL,
  thumb_key     text,
  mime          text NOT NULL,
  width         int,
  height        int,
  duration_ms   int,
  seed          bigint,
  model_hash    text,
  prompt_snapshot jsonb,
  nsfw          boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE edit_packages (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES projects(id),
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  revision_id   uuid NOT NULL REFERENCES prompt_revisions(id),
  storage_key   text NOT NULL,
  edit_list     jsonb NOT NULL,
  created_by    uuid NOT NULL REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE credit_ledger (
  id            uuid PRIMARY KEY,
  workspace_id  uuid NOT NULL REFERENCES workspaces(id),
  job_id        uuid REFERENCES generation_jobs(id),
  delta         numeric(12,4) NOT NULL,
  balance_after numeric(12,4) NOT NULL,
  reason        text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
  id            uuid PRIMARY KEY,
  workspace_id  uuid,
  user_id       uuid,
  action        text NOT NULL,
  entity_type   text NOT NULL,
  entity_id     uuid,
  meta          jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_ws ON projects(workspace_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_jobs_state ON generation_jobs(state, created_at);
CREATE INDEX idx_jobs_ws ON generation_jobs(workspace_id, created_at DESC);
CREATE INDEX idx_assets_project ON assets(project_id, created_at DESC);
CREATE INDEX idx_revisions_project ON prompt_revisions(project_id, revision_no);
CREATE INDEX idx_audit_ws ON audit_logs(workspace_id, created_at DESC);
CREATE INDEX idx_users_email ON users(email);
