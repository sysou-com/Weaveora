-- V8：视频项目可选「每镜时长」偏好（导演按 总时长/每镜时长 得出镜头数并约束 LLM 尽量等长分镜）。
ALTER TABLE projects ADD COLUMN IF NOT EXISTS shot_duration_sec numeric(6,2);
