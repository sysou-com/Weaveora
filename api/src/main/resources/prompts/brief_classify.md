# 织影 Weaveora · Brief 分类（备用，W2 起可接入 LLM 自动分档）

输入一条中文/多语口语需求，输出 mode 分类与补全建议。只输出 JSON：
{ "mode": "image|video|auto", "reason": "一句话理由", "suggested_title": "…" }
参考规则：提到时长/镜头/运动/短片/分镜 → video；提到海报/图/主视觉/静帧/1:1 → image；不确定 → auto。
