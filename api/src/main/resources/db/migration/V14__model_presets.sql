-- V14：已配置模型库（同一个用户可以存多个模型配置，界面上做成下拉选择）
--
-- 每条保存：baseUrl + 模型名 + 调过的参数 + 网关上限/示例 + 该模型的参数说明（schema）。
-- 存 JSON 数组而不是新表：条目少、读写都在用户配置这一行内，省掉一套表/仓储/事务，
-- 也避免给「用户级配置」再引入一个聚合根。

ALTER TABLE user_engine_settings
    ADD COLUMN IF NOT EXISTS image_model_presets jsonb,
    ADD COLUMN IF NOT EXISTS video_model_presets jsonb;
