-- V12：模型参数说明的「拉取失败原因」+ 网关通道的参考图上限
--
-- 背景（2026-09 实测）：
--   1) 换了模型但拉取失败时，旧代码把**上一个模型的 schema** 留在库里 → 界面继续显示
--      「参考图字段 input_file 只收单张 / 7 个参数」，而那其实是 comfyui/any-comfyui-workflow 的。
--      → 失败必须清空并记录原因。
--   2) 网关通道（如火山方舟 https://ark.cn-beijing.volces.com/api/v3/images/generations）
--      不是 Replicate，无法自动拉 schema；参考图字段与张数上限只能由用户按官方文档填写。

ALTER TABLE user_engine_settings
    ADD COLUMN IF NOT EXISTS image_model_schema_error text,
    ADD COLUMN IF NOT EXISTS video_model_schema_error text,
    -- 网关通道：本模型单次最多参考图张数（0/空 = 未知，worker 用内置默认值）
    ADD COLUMN IF NOT EXISTS gateway_refs_max integer;
