-- ===== W3 Worker/Jobs 支撑（§19/§20；简化额度 §22.2）=====

-- Worker 节点注册表（双轨同构：BYO 绑 workspace，我方节点池 workspace_id = NULL）
CREATE TABLE IF NOT EXISTS worker_nodes (
  id            uuid PRIMARY KEY,
  workspace_id  uuid REFERENCES workspaces(id),
  name          text NOT NULL,
  capabilities  jsonb NOT NULL DEFAULT '{}',
  last_seen_at  timestamptz NOT NULL DEFAULT now(),
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- 取消请求（§9.3 允许取消：worker 拉取/回执时识别）
ALTER TABLE generation_jobs ADD COLUMN IF NOT EXISTS cancel_requested boolean NOT NULL DEFAULT false;

-- 默认 Model Preset（§7.8/§11.2 stub 档；固定 UUID 幂等）
INSERT INTO model_presets (id, slug, kind, workflow_id, display_name, cost_unit, cost_credits, enabled) VALUES
  ('11111111-1111-7111-8111-111111111111', 'stub-txt2img', 'image', 'stub_txt2img', '开发占位 · 出图', 'per_image', 0, true),
  ('22222222-2222-7222-8222-222222222222', 'stub-motion',  'video', 'stub_motion',  '开发占位 · 运动', 'per_clip', 0, true)
ON CONFLICT (slug) DO NOTHING;
