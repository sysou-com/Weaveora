<script setup lang="ts">
const contact = {
  qq: '358532433',
  email: 'sysou.com@outlook.com',
}
</script>

<template>
  <div class="page">
    <p class="eyebrow font-mono">ABOUT</p>
    <h1 class="title">关于织影 Weaveora</h1>
    <section class="cardz">
      <article class="item">
        <h3>我们要解决什么</h3>
        <p class="body">
          做“图/视频”的工作，常常卡在“想法 → 分镜 → 素材 → 成片”这一长串低效步骤上：
          提示词写不专业、主体不一致、逐条生成又贵又慢、协作和审批一团乱。
          织影把这些环节收进一条可确认、可复用的流水线，让“一句话”能顺畅变成画面与短片。
        </p>
      </article>
      <article class="item">
        <h3>织影怎么解决</h3>
        <ul>
          <li><b>导演层</b>：LLM 把 Brief 织成可编辑的镜头/提示词方案，先确认再生成（确认闸门）。</li>
          <li><b>一致性</b>：参考图 + IP-Adapter 保证同一主体跨镜一致；种子锁定可复现。</li>
          <li><b>引擎中立</b>：图片/视频可跑在云端或你自己的 GPU（Comfy 系），按能力切换。</li>
          <li><b>资产与交付</b>：关键帧、运动片段、成片都进资产库；一键成片包 / edit_list 供精修。</li>
          <li><b>分享与集市</b>：作品分享待审后进入集市，他人可看可赞可收藏，形成作品池。</li>
        </ul>
      </article>
      <article class="item">
        <h3>联系开发者</h3>
        <p class="body">
          QQ：{{ contact.qq }}<br />
          邮箱：<a :href="`mailto:${contact.email}`">{{ contact.email }}</a>
        </p>
      </article>
    </section>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 20px; }
.eyebrow { margin: 0 0 8px; font-size: 11px; letter-spacing: 0.34em; color: var(--wv-text-4); }
.title { font-size: 30px; line-height: 1.2; }
.cardz { display: flex; flex-direction: column; gap: 16px; max-width: 760px; }
.item { background: var(--wv-surface); border: 1px solid var(--wv-line); border-radius: var(--wv-radius-m); padding: 18px 20px; }
.item h3 { margin: 0 0 10px; font-size: 17px; font-family: var(--wv-font-display); }
.body { margin: 0; font-size: 13.5px; line-height: 1.9; color: var(--wv-text-2); }
.item ul { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 8px; }
.item li { font-size: 13.5px; line-height: 1.8; color: var(--wv-text-2); }
.item a { color: var(--wv-accent-text); }
</style>
