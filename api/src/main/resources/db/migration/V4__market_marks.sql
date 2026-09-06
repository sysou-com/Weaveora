-- V4：集市点赞/收藏（project_marks：一用户对一项目 like/fav 各一票）

CREATE TABLE project_marks (
    project_id  uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('like', 'fav')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id, kind)
);

CREATE INDEX idx_marks_proj_kind ON project_marks(project_id, kind);
