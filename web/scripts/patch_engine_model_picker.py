#!/usr/bin/env python3
"""生成引擎配置页改版：① 里选「图像模型 / 视频模型」→ ②③ 只显示与所选模型有关的配置。

背景（2026-09-23 用户口径）：
  · 保持 ① 引擎开关；引擎 = GPU 服务器（自有引擎）时，图片生成引擎给「图像模型」下拉、
    视频生成引擎给「视频模型」下拉；
  · 选完模型后，下面的 ② GPU 服务器 与 ③ 服务地址 只显示与该模型有关的配置与提示；
  · 每行上下间距加宽、排版重排（原来是所有字段一次性铺满，密到看不清）。

本脚本是**一次性的结构化补丁**（按行号整体替换 ②③ 两张卡 + 插入 ① 的两个下拉），
幂等：已打过（② 里出现 cnNum(numGpu)）就直接退出。
"""
import io

P = "web/src/views/EngineSettingsView.vue"
s = io.open(P, encoding="utf-8").read()

if "cnNum(numGpu)" in s:
    print("already patched")
    raise SystemExit(0)

# ────────────────────────── ① 引擎开关：插入两个模型下拉（文本锚点） ──────────────────────────
anchor_1 = """        <NFormItem label="视频生成引擎">
          <NRadioGroup v-model:value="videoEngine" data-testid="vid-engine">
            <NRadio v-for="o in engineOptions" :key="o.value" :value="o.value" :label="o.label" />
          </NRadioGroup>
        </NFormItem>
      </section>"""

new_1 = """        <NFormItem label="视频生成引擎">
          <NRadioGroup v-model:value="videoEngine" data-testid="vid-engine">
            <NRadio v-for="o in engineOptions" :key="o.value" :value="o.value" :label="o.label" />
          </NRadioGroup>
        </NFormItem>

        <!-- ★ 2026-09-23：GPU 自有引擎 → 先选模型。选了它，下面 ②/③ 只显示与该模型有关的配置。 -->
        <div v-if="imageEngine === 'gpu' || videoEngine === 'gpu'" class="model-picks">
          <NFormItem v-if="imageEngine === 'gpu'" label="图像模型（GPU · 自有引擎）">
            <div class="field-col">
              <NSelect
                :value="imageModelKey"
                :options="gpuImageModelOptions"
                data-testid="gpu-image-model"
                @update:value="(v: string | null) => onImageModelChange(v)"
              />
              <p class="mdl-hint text-secondary">{{ imageModel.desc }}</p>
            </div>
          </NFormItem>
          <NFormItem v-if="videoEngine === 'gpu'" label="视频模型（GPU · 自有引擎）">
            <div class="field-col">
              <NSelect
                :value="videoModel"
                :options="gpuVideoModelOptions"
                data-testid="gpu-video-model"
                @update:value="(v: string | null) => (videoModel = v ?? 'wan22')"
              />
              <p class="mdl-hint text-secondary">{{ videoModelDesc }}</p>
            </div>
          </NFormItem>
        </div>
      </section>"""
NEW_GPU = r"""      <section class="card">
        <h2 class="card-title">{{ cnNum(numGpu) }} GPU 服务器（自有引擎）</h2>
        <p class="hint text-secondary">
          你自备的 GPU 引擎地址。<b>换实例后 IP 与端口都会变</b>：只改这一张卡，再点「一键同步 IP / 端口」即可全量改完。
          下面<b>只显示与 ① 里所选模型相关</b>的配置 —— 换模型请回 ① 重选。
        </p>
        <div class="field-grid">
          <NFormItem label="URL" class="span-all">
            <NInput v-model:value="gpuServerUrl" placeholder="如 http://your-gpu-host 或留空使用池节点" />
          </NFormItem>
          <NFormItem label="端口">
            <NInputNumber v-model:value="gpuServerPort" :min="1" :max="65535" placeholder="8188" style="width: 100%" />
          </NFormItem>
        </div>
        <div class="sync-bar">
          <NButton size="small" secondary data-testid="sync-gpu-address" @click="openSync">
            <template #icon><NIcon><RefreshCw :size="14" /></NIcon></template>
            一键同步 IP / 端口
          </NButton>
          <span class="hint text-secondary" style="margin: 0">
            换实例后 <b>IP 与端口都会变</b>：这里一次把上面的 GPU 服务器地址 + 下面「服务地址」里所有
            <b>已显式填过</b>的 URL 全换掉，并列出替换清单与改漏清单。
          </span>
        </div>
        <p v-if="envStatus" class="hint text-secondary env-line" style="margin: -4px 0 14px">
          <b>worker 回退值</b>（<code>{{ envStatus.file }}</code> · 服务 <code>{{ envStatus.service }}</code> ·
          {{ envStatus.serviceState }}）：
          <template v-if="envStatus.available">
            <code v-for="(v, k) in envStatus.values" :key="k">{{ v }}</code>
            <span v-if="envMismatch.length" class="env-warn">
              ⚠ 有 {{ envMismatch.length }} 项与上面的页面地址不一致 —— 点「一键同步 IP / 端口」可一并改掉
            </span>
            <span v-else>✓ 与页面地址一致</span>
          </template>
          <template v-else>
            本机读不到这个文件 → 这些地址以页面为准（页面留空时会回退到 worker 本机默认值）
          </template>
        </p>

        <!-- 图片模型相关：只在图片引擎 = GPU 时显示 -->
        <template v-if="imageEngine === 'gpu'">
          <NDivider style="margin: 8px 0 16px" />
          <h3 class="sub-title">图片出图 · {{ imageModel.label }}</h3>
          <p class="hint text-secondary">{{ imageModel.desc }}</p>
          <NFormItem label="图片分辨率（关键帧 / 定妆照 / 参考图，全局生效）">
            <NSelect v-model:value="imageMaxResolution" :options="imageResOptions" />
          </NFormItem>
          <p class="hint text-secondary">
            档位按 16:9 口径标注实际出图尺寸（其它画幅按同比例 32 对齐缩放）。越高越清晰（关键帧细节会带进视频底图），代价是更慢、更占显存。
            <b>出图工作流 / 步数 / cfg / denoise</b> 在下面「{{ cnNum(numSvc) }} 服务地址 → 图片出图」里。
          </p>
        </template>

        <!-- 视频模型相关：只在视频引擎 = GPU 时显示 -->
        <template v-if="videoEngine === 'gpu'">
          <NDivider style="margin: 8px 0 16px" />
          <h3 class="sub-title">视频出片 · {{ videoModel === 'ltx25' ? 'LTX-2.5' : 'Wan2.2 I2V-A14B' }}</h3>
          <p class="hint text-secondary">{{ videoModelDesc }}</p>

          <!-- Wan2.2 专属：分辨率 + 档位旋钮（LTX-2.5 不用这些） -->
          <template v-if="videoModel === 'wan22'">
            <NFormItem label="视频分辨率（出片上限；换 GPU 卡就改这里）" style="max-width: 460px">
              <NSelect v-model:value="gpuMaxResolution" :options="gpuMaxResOptions" />
            </NFormItem>
            <p class="hint text-secondary">
              实测 48G 卡：<b>720p / 48 帧要 10 分钟以上且易超时</b>，<b>480p 只要 27~50 秒</b>；A14B 的甜点也是 480p 级。
            </p>
            <p class="hint text-secondary">
              <b>motion 档位</b>：高噪声专家负责大幅运动，<b>默认不蒸馏（lora_high=0）</b>，动态靠 steps / switch / cfg 调；
              保存后随任务下发（无需重启 worker），留空 = 用 worker 默认档 <code>balanced</code>。
              <b>切档会记忆参数</b>：下次切回该档自动用上次的值填充覆盖（存在引擎配置里，手机端也生效）。
            </p>
            <div class="field-grid">
              <NFormItem label="档位 preset">
                <NSelect
                  :value="(videoParams.preset as string) ?? null"
                  :options="motionPresetOptions"
                  clearable
                  placeholder="balanced"
                  @update:value="(v: string | null) => onPresetChange(v)"
                />
              </NFormItem>
              <NFormItem label="步数 steps">
                <NInputNumber :value="numOf(videoParams.steps)" :min="1" :max="40" placeholder="档位默认"
                              style="width: 100%" @update:value="(v: number | null) => mSet('steps', v)" />
              </NFormItem>
              <NFormItem label="切换步 switch">
                <NInputNumber :value="numOf(videoParams.switch_step)" :min="1" :max="40" placeholder="总步数折半"
                              style="width: 100%" @update:value="(v: number | null) => mSet('switch_step', v)" />
              </NFormItem>
              <NFormItem label="cfg 高噪声">
                <NInputNumber :value="numOf(videoParams.cfg_high)" :min="0" :max="10" :step="0.5"
                              placeholder="1.0（hero=3.5）" style="width: 100%"
                              @update:value="(v: number | null) => mSet('cfg_high', v)" />
              </NFormItem>
              <NFormItem label="cfg 低噪声">
                <NInputNumber :value="numOf(videoParams.cfg_low)" :min="0" :max="10" :step="0.5" placeholder="1.0"
                              style="width: 100%" @update:value="(v: number | null) => mSet('cfg_low', v)" />
              </NFormItem>
              <NFormItem label="LoRA 高噪声">
                <NInputNumber :value="numOf(videoParams.lora_high)" :min="0" :max="1.5" :step="0.1"
                              placeholder="0（不蒸馏，推荐）" style="width: 100%"
                              @update:value="(v: number | null) => mSet('lora_high', v)" />
              </NFormItem>
              <NFormItem label="LoRA 低噪声">
                <NInputNumber :value="numOf(videoParams.lora_low)" :min="0" :max="1.5" :step="0.1" placeholder="1.0"
                              style="width: 100%" @update:value="(v: number | null) => mSet('lora_low', v)" />
              </NFormItem>
              <NFormItem label="分辨率（Wan 档位）">
                <NSelect
                  :value="(videoParams.resolution as string) ?? '480p'"
                  :options="motionResOptions"
                  @update:value="(v: string | null) => mSet('resolution', v ?? '480p')"
                />
              </NFormItem>
              <NFormItem label="shift">
                <NInputNumber :value="numOf(videoParams.shift)" :min="0" :max="20" :step="0.5" placeholder="5.0"
                              style="width: 100%" @update:value="(v: number | null) => mSet('shift', v)" />
              </NFormItem>
            </div>
            <NFormItem label="高级：直接编辑 motion JSON（白名单键，任一子集即可）">
              <NInput
                v-model:value="motionJson"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
                placeholder='如 {"preset":"motion","lora_high":0}'
                @blur="applyMotionJson"
              />
            </NFormItem>
          </template>

          <!-- LTX-2.5 专属：它走自己的两段式，没有 Wan 的档位 / 分辨率旋钮 -->
          <template v-else>
            <NFormItem label="帧率（时间轴 ×2 → 48fps；24fps 为原生）" style="max-width: 380px">
              <NSelect
                :value="numOf(videoParams.fps_x2) ? 1 : 0"
                :options="motionFpsOptions"
                @update:value="(v: number | null) => mSet('fps_x2', v ? 1 : 0)"
              />
            </NFormItem>
            <p class="hint text-secondary">
              LTX-2.5 走自己的两段式：<b>分辨率由项目画幅决定</b>（长边上限 1280，单镜时长 ≤20s，输出 24fps 且自带音轨），
              所以这里没有「视频分辨率 / steps / cfg / LoRA / 档位」—— 那些只对 Wan2.2 生效。
              实测 24fps 145s / 48fps 156s（+7.7%），峰值显存 42.7~43.0 GiB，<b>同一时间只能跑一个 GPU 任务</b>。
              成片帧率由交付口按引擎归一（24；开 ×2 则 48），不用手改。
            </p>
          </template>
        </template>
      </section>"""

# ────────────────────────── ③ 服务地址：按模型分组 + 折叠 ──────────────────────────
NEW_SVC = r"""      <section class="card" data-testid="svc-card">
        <h2 class="card-title">{{ cnNum(numSvc) }} 服务地址（换 GPU 机器只改这里）</h2>
        <p class="hint text-secondary">
          ★ <b>GPU 公网 IP/端口会变</b>：只改上面的「GPU 服务器地址 + 端口」，下面这些<b>留空即自动跟随</b>
          （后端会推导成 <code>&lt;gpu&gt;:8001</code>（ComfyUI）、<code>&lt;gpu&gt;/audio</code>（配音/转写）、
          <code>&lt;gpu&gt;/talk</code>（整脸口型））。<b>不要</b>把 IP 写进 worker 脚本、部署脚本或代码。
          下面按模型分组：与所选模型无关的组已折叠（点标题展开即可改）。
        </p>

        <!-- 与 ① 里所选「图像模型」对应：只有图片引擎 = GPU 时才需要它 -->
        <template v-if="imageEngine === 'gpu'">
          <NDivider style="margin: 10px 0 4px" />
          <h3 class="sub-title">图片出图 · {{ imageModel.label }}</h3>
          <p class="hint text-secondary">
            三份工作流是 <b>worker 机器上的绝对路径</b>（装在哪台机就填哪台的）；上面的「图像模型」下拉已按该模型填好
            文件名与 steps / cfg / denoise。要换模型请回 ① 重选，一般不用在这里手改。
          </p>
          <div class="field-grid">
            <NFormItem label="出图引擎">
              <NSelect v-model:value="svcImageEngine" :options="[
                { label: 'comfy（本机 ComfyUI 工作流）', value: 'comfy' },
                { label: '内置（worker 自带 SDXL）', value: 'builtin' },
              ]" />
            </NFormItem>
            <NFormItem label="ComfyUI 地址">
              <NInput v-model:value="svcImageComfy" placeholder="留空=自动 &lt;GPU 服务器&gt;:8001" />
            </NFormItem>
          </div>
          <NFormItem label="文生图工作流 txt2img（API 格式 JSON 绝对路径）">
            <NInput v-model:value="svcImageWorkflow" placeholder="如 /opt/weaveora/workflows/qwen_image_txt2img_film_api.json" />
          </NFormItem>
          <NFormItem label="参考图锚定工作流 editWorkflow（有参考图就优先走它）">
            <NInput v-model:value="svcImageEdit" placeholder="如 /opt/weaveora/workflows/qwen_image_edit_api.json" />
          </NFormItem>
          <NFormItem label="图生图工作流 img2img（关键帧当底图；可留空）">
            <NInput v-model:value="svcImageImg2img" placeholder="如 /opt/weaveora/workflows/qwen_image_img2img_api.json" />
          </NFormItem>
          <p class="hint text-secondary">
            优先级：有参考图且有 editWorkflow → <b>Edit（参考图锚定）</b>；否则 img2img；再否则 txt2img。
          </p>
          <div class="field-grid">
            <NFormItem label="主模型名（留空=用工作流里的）">
              <NInput v-model:value="svcImageModel" placeholder="如 qwen_image_fp8_e4m3fn.safetensors" />
            </NFormItem>
            <NFormItem label="步数 steps">
              <NInputNumber v-model:value="svcImageSteps" :min="1" :max="60" placeholder="40" style="width: 100%" />
            </NFormItem>
            <NFormItem label="cfg（提示词遵从度）">
              <NInputNumber v-model:value="svcImageCfg" :min="0" :max="12" :step="0.5"
                            placeholder="4.0 / 留空=工作流默认" style="width: 100%" />
            </NFormItem>
            <NFormItem label="图生图 denoise">
              <NInputNumber v-model:value="svcImageDenoise" :min="0.1" :max="1" :step="0.05" placeholder="0.65"
                            style="width: 100%" />
            </NFormItem>
          </div>
          <p v-if="svcImageLora" class="hint text-secondary">
            当前档挂 LoRA：<code>{{ svcImageLora }}</code>（强度 {{ svcImageLoraStrength ?? 1 }}；FLUX.2 档 steps
            {{ svcImageLoraStepsFlux2 ?? 8 }} / guidance {{ svcImageLoraCfg ?? 4 }}）。想不挂 LoRA 请回 ① 选不带 LoRA 的模型。
          </p>
        </template>

        <!-- 视频后期：出片之后的对口型链，跟「视频模型」走 -->
        <NDivider style="margin: 10px 0 4px" />
        <h3 class="sub-title">视频后期 · 对口型 / 整脸口型 / 人脸</h3>
        <NCollapse :default-expanded-names="videoEngine === 'gpu' ? ['lip'] : []">
          <NCollapseItem title="对口型 ComfyUI · 工作流 · 整脸口型 talk · 人脸（点开编辑）" name="lip">
            <NFormItem label="对口型 ComfyUI 地址">
              <NInput v-model:value="svcLipsyncComfy" placeholder="如 http://127.0.0.1:8188（留空=默认/用上面的 GPU 服务器）" />
            </NFormItem>
            <NFormItem label="对口型工作流（API 格式 JSON 的绝对路径，装在哪台机就填哪台的路径）">
              <NInput v-model:value="svcLipsyncWorkflow" placeholder="如 D:\ComfyUI\_setup\lipsync_workflow_api.json" />
            </NFormItem>
            <div class="field-grid">
              <NFormItem label="对口型超时（秒）">
                <NInputNumber v-model:value="svcLipsyncTimeout" :min="60" :max="14400" placeholder="1800" style="width: 100%" />
              </NFormItem>
              <NFormItem label="输出帧率（0=跟随源片）">
                <NInputNumber v-model:value="svcLipsyncFps" :min="0" :max="60" placeholder="0" style="width: 100%" />
              </NFormItem>
            </div>
            <NFormItem label="人脸服务地址（留空 = 用 worker 本机 insightface）">
              <NInput v-model:value="svcFaceUrl" placeholder="如 http://127.0.0.1:8093（deploy/face/face_server.py）" />
            </NFormItem>
            <NFormItem label="人脸/LatentSync 节点目录（本机人脸检测用，可留空）">
              <NInput v-model:value="svcFaceDir" placeholder="如 D:\ComfyUI\custom_nodes\ComfyUI-LatentSyncWrapper" />
            </NFormItem>
            <p class="hint text-secondary">
              <b>整脸口型（EchoMimicV3 / jaw-lip）</b>：喊叫、尖叫、吟唱这类「嘴大张」镜用它替代 LatentSync，
              跑在 GPU 机的 <code>talk_server.py</code>（默认 :8094，网关路径 <code>/talk</code>）。留空 = 自动用 GPU 服务器地址推导。
            </p>
            <div class="field-grid">
              <NFormItem label="整脸口型服务地址">
                <NInput v-model:value="svcTalkUrl" placeholder="留空=自动 &lt;GPU 服务器&gt;/talk" />
              </NFormItem>
              <NFormItem label="下颌曲线增益 jaw_gain">
                <NInputNumber v-model:value="svcTalkJawGain" :min="0" :max="2" :step="0.05" placeholder="1.0" style="width: 100%" />
              </NFormItem>
            </div>
            <p class="hint text-secondary">
              <code>jaw_gain=1.0</code> 零改动（原生 EchoMimic 输出）；<code>1.25</code> 是增强档（喊叫时下半脸张得更开）。
            </p>
          </NCollapseItem>
        </NCollapse>

        <!-- 音频链：与图片 / 视频模型无关，默认折叠，避免干扰 -->
        <NDivider style="margin: 10px 0 4px" />
        <h3 class="sub-title">配音 / 配乐 / 转写</h3>
        <NCollapse>
          <NCollapseItem title="配音 TTS · 配乐 ACE-Step · 语音转文字（点开编辑）" name="audio">
            <NFormItem label="配音（TTS）服务地址">
              <NInput v-model:value="svcTtsUrl" placeholder="如 http://127.0.0.1:8091（留空=默认）" />
            </NFormItem>
            <div class="field-grid">
              <NFormItem label="配乐引擎">
                <NSelect v-model:value="svcMusicEngine" :options="[
                  { label: 'comfy（本机 ComfyUI 里的 ACE-Step）', value: 'comfy' },
                  { label: 'http（独立音乐服务）', value: 'http' },
                ]" />
              </NFormItem>
              <NFormItem label="配乐服务地址">
                <NInput v-model:value="svcMusicUrl" placeholder="如 http://127.0.0.1:8092（留空=默认）" />
              </NFormItem>
            </div>
            <NFormItem label="配乐权重名（engine=comfy 时用）">
              <NInput v-model:value="svcMusicCkpt" placeholder="如 ace_step_1.5_turbo_aio.safetensors（留空=默认）" />
            </NFormItem>
            <NFormItem label="转写（语音转文字）服务地址">
              <NInput v-model:value="svcTranscribeUrl" placeholder="如 http://127.0.0.1:8091（留空=默认；通常与配音同一台机）" />
            </NFormItem>
          </NCollapseItem>
        </NCollapse>
      </section>"""

lines = s.split("\n")   # ★ 先用原始行号定位 ②③（① 的插入会移位）
assert lines[731].strip() == '<section class="card">', lines[731]
assert "GPU 服务器" in lines[732], lines[732]
assert lines[873].strip() == "</section>", lines[873]
assert lines[875].strip() == '<section class="card" data-testid="svc-card">', lines[875]
assert lines[990].strip() == "</section>", lines[990]

out = lines[:731] + NEW_GPU.split("\n") + lines[874:875] + NEW_SVC.split("\n") + lines[991:]
s = "\n".join(out)
# ① 的插入文本锚点最后做（它在上方，不影响上面的行号定位）
assert s.count(anchor_1) == 1, s.count(anchor_1)
s = s.replace(anchor_1, new_1, 1)
io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("cards patched")
