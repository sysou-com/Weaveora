-- V10：分镜封版（shot_locks）
-- 语义：某镜「资源已满足要求」→ 后续批量生成（关键帧 / motion / 配音）默认跳过它。
-- 关键点：按 (project_id, shot_no) 存，**不是** shot_id —— shot_drafts 每次 patch/approve 都会
--         delete+recreate（行 id 漂移，见 V9），只有 shot_no 跨版本稳定。

CREATE TABLE IF NOT EXISTS shot_locks (
    id           uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    project_id   uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    shot_no      integer NOT NULL,
    note         text,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_shot_locks_project_shot
    ON shot_locks (project_id, shot_no);

CREATE INDEX IF NOT EXISTS idx_shot_locks_workspace_project
    ON shot_locks (workspace_id, project_id);
