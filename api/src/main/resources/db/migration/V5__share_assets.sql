-- V5：分享时可指定“仅展示选中素材”（集市只显示这些资产）

CREATE TABLE project_share_assets (
    project_id  uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    asset_id    uuid NOT NULL REFERENCES assets(id)    ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, asset_id)
);
CREATE INDEX idx_share_assets_proj ON project_share_assets(project_id);
