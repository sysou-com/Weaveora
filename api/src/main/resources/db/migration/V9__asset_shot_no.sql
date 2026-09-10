-- P6: assets 增加 shot_no 冗余列并回填
-- 背景：shot_drafts 每次 patch/approve 都会 delete+recreate（shot 行 id 漂移），
--       导致按 shot_id 查关键帧/素材找不到（motion 误报“尚无关键帧”、导出丢素材）。
ALTER TABLE assets ADD COLUMN IF NOT EXISTS shot_no integer;

CREATE INDEX IF NOT EXISTS idx_assets_project_shot_kind ON assets (project_id, shot_no, kind);

UPDATE assets a
SET shot_no = sd.shot_no
FROM shot_drafts sd
WHERE a.shot_id = sd.id
  AND a.shot_no IS NULL;
