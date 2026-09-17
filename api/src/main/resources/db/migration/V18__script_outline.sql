-- ============================================================
-- V18：剧本 AI 提纲持久化
--   scripts.outlines       : {"characters":["1. …","2. …"], "story":[…]}
--                            —— 「AI 更新」时若段数一致则**复用同一份提纲**（省一次调用、避免每次换结构）
--   script_episodes.outline: ["1. 起 —— …","2. 承转 —— …"]
--                            —— 该集是照着哪份提纲写出来的（重开能看到，便于续写与复盘）
-- ============================================================

ALTER TABLE scripts ADD COLUMN outlines jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE script_episodes ADD COLUMN outline jsonb NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN scripts.outlines IS '各要素的分段写作提纲（key=ScriptField.key → 每段一行）；AI 更新时按段数复用';
COMMENT ON COLUMN script_episodes.outline IS '本集的节拍提纲（每段一行）；由生成时回传并随集保存';
