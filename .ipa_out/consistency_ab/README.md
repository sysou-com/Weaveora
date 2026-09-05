# IP-Adapter 一致性 —— 如何确认机制真的生效

## 看这张图
`ab_compare.png`（或分别看三张原图）：

| 图 | 说明 |
|---|---|
| ① 00_ref.png | 参考图（真出图：青瓷白梅茶壶） |
| ② ab_no_ref.png | **对照**：同 prompt、同 seed，但**不注入参考图**（负样本） |
| ③ ab_with_ref.png | **实验**：同 prompt、同 seed，**注入 ①** |

判定标准：
- ② 与 ③ 的唯一差别是「是否注入参考图」→ 二者若不同，说明 IP-Adapter 在起作用；
- ③ 的茶壶造型/釉色/白梅位置应明显贴近 ①（同主体、同视觉身份）；
- ② 大概率是「另一个壶」（泛化重画）。
- 若 ③ ≈ ②（几乎一样）或 ③ 与 ① 差异很大 → 一致性未生效，需排查。

再回看上一批 `../contact.png`（01–04 四张）：它们共享 ① 作参考 + 同一文案 × 4 个随机种子，
主体应是同一只壶（构图/光线略变是允许的，身份不能变）。

## 工程侧已提供的硬证据
1. 验收运行在 `WEAVEORA_COMFY_FALLBACK_TXT2IMG=0` 下：IP-Adapter 工作流一旦失败会直接报错，
   没有静默降级 txt2img 的余地；当时 4/4 succeeded。
2. 每个 job payload 都带 `referenceKeys` → 引擎实际把 ① 上传 Comfy 并走了 `IPAdapter` 节点
   （早期接线错误曾报 `tuple index out of range`，已按真实节点签名修正后才通过）。
