-- V6：系统风格模板种子（is_system；workspace_id NULL）。
-- 风格以 prompt_prefix 注入正向词（SDXL/Wan 对英文风格词响应最好），negative 留空沿用引擎默认。
INSERT INTO style_templates (id, workspace_id, slug, name, prompt_prefix, prompt_suffix, negative, default_params, is_system, created_at) VALUES
('00000000-0000-4000-8000-000000000001', NULL, 'default',   '默认（跟随描述）', '', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000002', NULL, 'photography', '写实摄影', 'Professional photography, photorealistic, natural lighting, sharp details, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000003', NULL, 'cinematic', '电影质感', 'Cinematic film still, dramatic cinematic lighting, shallow depth of field, 35mm film, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000004', NULL, 'anime', '日系动漫', 'Japanese anime style, clean line art, vibrant cel shading, high detail, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000005', NULL, 'inkwash', '中国水墨', 'Chinese ink wash painting, elegant brush strokes, minimalist, rice paper texture, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000006', NULL, 'watercolor', '清新水彩', 'Watercolor painting, soft color washes, visible paper grain, gentle, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000007', NULL, 'oilpainting', '古典油画', 'Classical oil painting, rich brushstrokes, chiaroscuro lighting, gallery quality, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000008', NULL, 'cyberpunk', '赛博朋克', 'Cyberpunk style, neon-lit city, high contrast, cinematic sci-fi, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-000000000009', NULL, 'minimal', '极简留白', 'Minimalist composition, abundant negative space, soft muted tones, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-00000000000a', NULL, 'filmretro', '复古胶片', 'Retro 35mm film photography, film grain, warm faded tones, analog, ', '', '', '{}'::jsonb, true, now()),
('00000000-0000-4000-8000-00000000000b', NULL, 'render3d', '3D 渲染', 'High quality 3D render, soft studio lighting, octane render, detailed, ', '', '', '{}'::jsonb, true, now());
