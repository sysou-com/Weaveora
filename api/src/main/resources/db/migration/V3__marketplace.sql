-- V3：项目集市（分享/审批）+ 项目“最近更新”触发器
-- 集市：客户分享待审(share_status=pending) → 管理员批准(approved)后进入集市；rejected 表示驳回。
-- “最近更新时间排序”：任何内容活动（brief/revision/job/asset）自动 touch 所属项目 updated_at。

ALTER TABLE projects ADD COLUMN share_status text;
ALTER TABLE projects ADD COLUMN shared_at   timestamptz;

CREATE INDEX idx_projects_share ON projects(share_status, updated_at DESC)
    WHERE share_status = 'approved' AND deleted_at IS NULL;

-- 项目活动 → touch updated_at（四张子表）
CREATE OR REPLACE FUNCTION weaveora_touch_project(pid uuid) RETURNS void AS $$
BEGIN
    UPDATE projects SET updated_at = now()
     WHERE id = pid AND deleted_at IS NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_touch_project_briefs() RETURNS trigger AS $$
BEGIN
    PERFORM weaveora_touch_project(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.project_id ELSE NEW.project_id END);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_touch_project_generic() RETURNS trigger AS $$
BEGIN
    PERFORM weaveora_touch_project(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.project_id ELSE NEW.project_id END);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proj_touch_briefs      ON briefs;
DROP TRIGGER IF EXISTS trg_proj_touch_revisions   ON prompt_revisions;
DROP TRIGGER IF EXISTS trg_proj_touch_jobs        ON generation_jobs;
DROP TRIGGER IF EXISTS trg_proj_touch_assets      ON assets;

CREATE TRIGGER trg_proj_touch_briefs
    AFTER INSERT OR UPDATE OR DELETE ON briefs
    FOR EACH ROW EXECUTE FUNCTION trg_touch_project_briefs();
CREATE TRIGGER trg_proj_touch_revisions
    AFTER INSERT OR UPDATE OR DELETE ON prompt_revisions
    FOR EACH ROW EXECUTE FUNCTION trg_touch_project_generic();
CREATE TRIGGER trg_proj_touch_jobs
    AFTER INSERT OR UPDATE OR DELETE ON generation_jobs
    FOR EACH ROW EXECUTE FUNCTION trg_touch_project_generic();
CREATE TRIGGER trg_proj_touch_assets
    AFTER INSERT OR UPDATE OR DELETE ON assets
    FOR EACH ROW EXECUTE FUNCTION trg_touch_project_generic();
