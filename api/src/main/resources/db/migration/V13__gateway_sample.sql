-- V13：网关通道的「示例请求」文本
--
-- 背景：火山方舟等网关只提供 /api/v3/models（模型元信息：模态、任务类型），
-- 没有机械可读的**逐参数说明**（不像 Replicate 有 openapi_schema），
-- 因此无法自动得知「参考图字段叫什么、最多几张、size 怎么填」。
-- 方案：允许用户粘贴一段**示例请求（curl 或 JSON body）**，后端解析出字段名与类型，
-- 生成与 Replicate 同形状的参数说明 + mapping（worker 直接复用同一套填参逻辑）。

ALTER TABLE user_engine_settings
    ADD COLUMN IF NOT EXISTS gateway_sample text;
