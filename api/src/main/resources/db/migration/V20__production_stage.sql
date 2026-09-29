-- ============================================================
-- V20：一键成片「制作流程」阶段确认（P0/P1，2026-09-29）
--
-- 背景：目标 UX = 用户只关注剧本，系统按「引擎能力 + 台词/旁白时长」自动拆镜，
--   用户依次点「确定/下一步」走完 定妆照 → 关键帧 → 运动 → 配音配乐 → 成片。
--
-- 这里只落**用户的确认动作**（谁 / 何时 / 哪一版方案 / 哪个阶段），满足 §0-3「生成必过用户确认闸门并落库」；
-- 阶段是否「跑完」由**已有的 generation_jobs + assets 派生**（不另做一套会卡住的 saga 状态机）。
--
-- stage ∈ portraits | keyframes | motion | audio | render
-- 同一 (project, revision, stage) 只留一条：重复点「确定」= 更新批准人/时间（幂等）。
-- ============================================================

CREATE TABLE IF NOT EXISTS production_stage_approvals (
  id          uuid PRIMARY KEY,
  project_id  uuid NOT NULL REFERENCES projects(id),
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  revision_id uuid NOT NULL,
  stage       text NOT NULL,
  approved_by uuid NOT NULL REFERENCES users(id),
  approved_at timestamptz NOT NULL DEFAULT now(),
  note        text,
  UNIQUE (project_id, revision_id, stage)
);

CREATE INDEX IF NOT EXISTS idx_stage_appr_project
    ON production_stage_approvals(project_id, revision_id);

COMMENT ON TABLE production_stage_approvals IS
    '制作流程阶段确认（P0/P1）：谁在何时确认了哪一版方案的下一个阶段；阶段完成度由 jobs/assets 派生';
