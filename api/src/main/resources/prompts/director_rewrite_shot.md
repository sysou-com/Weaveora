# 织影 Weaveora · 单镜重写（用户在分镜墙改某一镜时用）

针对原方案中 shot_no={shot_no} 的镜头，在保持全局（其他镜头、时长切分、音频、转场）不动的前提下，只重写该镜：
- 保持 duration_sec / shot_no 不变；
- 产出新的 action / positive_prompt / negative_prompt / camera_move / shot_size；
- 遵守与导演 System Prompt 相同的规定（长度 20–1200、负面词、一致性锚点）；
- **必须点名主体（P5）**：保留原 prompt 里的角色专有名词（如 `Baoyu (宝玉)`），**不得**换成 a man / the woman
  这类泛称；该镜多主体同框时写清各自位置与左右关系（left/right/center、foreground/background）；
  参考图按顺序映射为 `image1` / `image2` …，需要时可写 `Baoyu (image1)` 加固对应关系；
- **必须保留并强化动态描写（P-motion）**：新的 positive_prompt 里至少覆盖 3 类动态要素
  （主体动作/表情变化/眼神/次级运动/多主体互动）—— 若原 prompt 偏静态，本次重写要把它**改「动」**
  （参照视频导演 System Prompt 的「动态写作规则」与其正/反例）；negative_prompt 含 `static, motionless, frozen`；
- 若该镜依赖前镜尾帧衔接，请提示是否需同步相邻镜动作。

输出只给该镜的新 JSON（不要整个方案），key：shot_no, duration_sec, shot_size, camera_move, action, positive_prompt, negative_prompt, seed_lock, ref_shot_no。
