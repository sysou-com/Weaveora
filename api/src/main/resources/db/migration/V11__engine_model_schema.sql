-- V11：云模型「调用参数说明」缓存 + 用户全局参数（画质等）
--
-- 背景：云 API 模型的输入参数名各不相同（同样是参考图，有的叫 images、有的叫 input_images），
--      早期按模型名硬猜 → 猜错时 Replicate **静默忽略未知字段**，参考图根本没传进去（踩过：
--      flux-2-klein-9b 的参考图字段是 images，我们发的是 input_images，于是"一致性全无"）。
-- 方案：配置模型时主动拉取模型 input schema，落库缓存 → 前端展示精简调用说明；
--      任务下发时把 schema 归一化出的 mapping 一起给 worker，worker 按 mapping 填参数。

ALTER TABLE user_engine_settings
    ADD COLUMN IF NOT EXISTS image_model_schema    jsonb,
    ADD COLUMN IF NOT EXISTS video_model_schema    jsonb,
    ADD COLUMN IF NOT EXISTS image_model_schema_at timestamptz,
    ADD COLUMN IF NOT EXISTS video_model_schema_at timestamptz,
    ADD COLUMN IF NOT EXISTS image_params          jsonb,
    ADD COLUMN IF NOT EXISTS video_params          jsonb;
