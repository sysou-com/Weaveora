-- 图片分辨率（长边像素）：**全局生效**于所有出图（关键帧 / 定妆照 / 参考图）。
--
-- 为什么与 gpu_max_resolution 分开：出图（Qwen-Image）与出视频（Wan2.2 I2V）是两条独立链路，
-- 它们的性价比拐点完全不同（视频 480p 是甜点、720p 极慢；出图则是分辨率越高越清晰）。
-- 2026-09-18 用户口径：引擎配置的「GPU 服务器」里要能分别设置，图片支持到 2560×1440。
--
-- null = 默认 1280（长边）→ 16:9 出 1280×704，与历史行为完全一致（不改变存量项目）。
ALTER TABLE user_engine_settings
    ADD COLUMN IF NOT EXISTS image_max_resolution integer;

COMMENT ON COLUMN user_engine_settings.image_max_resolution IS
    '出图长边像素（512~4096，常用 1280/1920/2560）；null=默认 1280。按画幅等比缩放并 32 对齐';
