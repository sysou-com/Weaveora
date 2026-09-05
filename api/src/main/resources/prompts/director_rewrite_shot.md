# 织影 Weaveora · 单镜重写（用户在分镜墙改某一镜时用）

针对原方案中 shot_no={shot_no} 的镜头，在保持全局（其他镜头、时长切分、音频、转场）不动的前提下，只重写该镜：
- 保持 duration_sec / shot_no 不变；
- 产出新的 action / positive_prompt / negative_prompt / camera_move / shot_size；
- 遵守与导演 System Prompt 相同的规定（长度 20–1200、负面词、一致性锚点）；
- 若该镜依赖前镜尾帧衔接，请提示是否需同步相邻镜动作。

输出只给该镜的新 JSON（不要整个方案），key：shot_no, duration_sec, shot_size, camera_move, action, positive_prompt, negative_prompt, seed_lock, ref_shot_no。
