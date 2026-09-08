-- V7：生成引擎配置（用户级：图片/视频可分别选 GPU 或云；凭据 AES 密文存储）与任务路由列。
ALTER TABLE generation_jobs ADD COLUMN IF NOT EXISTS engine_route text NOT NULL DEFAULT 'gpu';

CREATE TABLE IF NOT EXISTS user_engine_settings (
  user_id                 uuid PRIMARY KEY REFERENCES users(id),
  image_engine            text NOT NULL DEFAULT 'gpu',   -- gpu | cloud
  video_engine            text NOT NULL DEFAULT 'gpu',   -- gpu | cloud
  -- 图片云（OpenAI Images 兼容：baseUrl + apiKey 或 basic 账号密码）
  image_cloud_base_url    text,
  image_cloud_auth_type   text NOT NULL DEFAULT 'api_key',
  image_cloud_api_key_cipher text,
  image_cloud_username    text,
  image_cloud_password_cipher text,
  image_cloud_model       text,
  -- 视频云（Replicate 通道：token 密文 + 模型名）
  video_cloud_api_key_cipher text,
  video_cloud_model       text,
  -- GPU 服务器（自有引擎绑定/健康探测；生成经 worker 池 comfy 节点）
  gpu_server_url          text,
  gpu_server_port         integer,
  updated_at              timestamptz NOT NULL DEFAULT now()
);
