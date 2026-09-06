<script setup lang="ts">
// 使用指南：结合织影操作流程整理的说明
</script>

<template>
  <div class="page">
    <header class="head">
      <p class="eyebrow font-mono">GUIDE</p>
      <h1 class="title">使用指南</h1>
      <p class="desc text-secondary">织影（Weaveora）：从一句话到一支镜头。</p>
    </header>

    <section class="cardz">
      <article class="item">
        <h3>① 账号与内测</h3>
        <ul>
          <li>注册后默认进入自己的工作区；当前内测阶段仅管理员可新建项目，其它用户如需创作请联系管理员开通。</li>
          <li>任何登录用户都可浏览「项目集市」、点赞/收藏他人分享。</li>
        </ul>
      </article>
      <article class="item">
        <h3>② 我的项目：一句话开始创作</h3>
        <ul>
          <li>在「我的项目」新建项目（图片/视频，可选手动时长、画面比例如 1:1 / 16:9 / 9:16）。</li>
          <li>填写 Brief（需求描述，至少 10 字），可上传 ≤4 张参考图做主体一致性。</li>
          <li>点「让导演层出方案」→ 得到可编辑方案；图片方案含提示词/镜头参数，视频方案含分镜（默认折叠，点开逐镜修改）。</li>
          <li>修正提示词/分镜后「保存修改」，校验通过后「确认方案」进入生成闸门。</li>
        </ul>
      </article>
      <article class="item">
        <h3>③ 生成与任务</h3>
        <ul>
          <li>图片项目：选张数(1/2/4)点「开始生成」；视频项目：先生成关键帧(still)，确认后再点「运动(motion)」得到短视频。</li>
          <li>任务面板实时显示进度；失败/已取消的任务可勾选「重试/删除」，也可批量操作。</li>
          <li>生成的图片与视频都会自动进入「资产库」预览（视频点击可沉浸播放）。</li>
        </ul>
      </article>
      <article class="item">
        <h3>④ 成片与交付</h3>
        <ul>
          <li>视频项目确认后：先生成关键帧 → 再生成运动片段 → 「渲染成片(mp4)」合成带转场与音轨的成片。</li>
          <li>「导出成片包(edit_list)」下载 edit_list.json + 素材，供剪映等外部精修（兼容可选项）。</li>
        </ul>
      </article>
      <article class="item">
        <h3>⑤ 分享与集市</h3>
        <ul>
          <li>在「我的项目」点「管理」勾选项目 → 「分享」提交集市，需管理员审批通过后上架。</li>
          <li>集市中的他人项目只读浏览、可点赞/收藏/下载预览图，不能编辑；管理员可在集市待审中批量通过/驳回/删除。</li>
        </ul>
      </article>
      <article class="item tip">
        <h3>小提示</h3>
        <ul>
          <li>画面比例（含竖屏 9:16）会自动决定生成尺寸；个别旧缓存页面请刷新一次让新版本生效。</li>
          <li>长视频按 30s/段自动分段导演并合并，耐心等待即可。</li>
        </ul>
      </article>
    </section>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 20px; }
.head {}
.eyebrow { margin: 0 0 8px; font-size: 11px; letter-spacing: 0.34em; color: var(--wv-text-4); }
.title { font-size: 30px; line-height: 1.2; }
.desc { margin: 8px 0 0; font-size: 14px; }
.cardz { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }
.item { background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); padding: 16px 18px; }
.item h3 { margin: 0 0 10px; font-size: 16px; font-family: var(--wv-font-display); }
.item ul { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; }
.item li { font-size: 13px; line-height: 1.7; color: var(--wv-text-2); }
.item.tip { border-color: color-mix(in srgb, var(--wv-accent) 40%, var(--wv-line)); }
</style>
