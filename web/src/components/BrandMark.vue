<script setup lang="ts">
/** 品牌标（§1.3）：片门圆角矩形 + 一条织线穿过。单色，16px 仍可识别。 */
withDefaults(
  defineProps<{
    size?: number
    tone?: 'bone' | 'teal'
  }>(),
  { size: 44, tone: 'bone' },
)
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    :aria-label="'织影 Weaveora'"
    role="img"
  >
    <!-- 片门圆角矩形（画幅框） -->
    <rect
      x="3.5"
      y="6"
      width="25"
      height="20"
      rx="4"
      :stroke="tone === 'teal' ? '#8FB9B4' : '#F3F0E8'"
      stroke-width="2"
    />
    <!-- 上下两边露出的织线 -->
    <path d="M1.5 16h7.5M23 16h7.5" :stroke="tone === 'teal' ? '#8FB9B4' : '#F3F0E8'" stroke-width="2" stroke-linecap="round" />
    <!-- 穿过片门的织线（斜织） -->
    <path d="M12.5 21.5L20.5 10.5" :stroke="tone === 'teal' ? '#8FB9B4' : '#F3F0E8'" stroke-width="2" stroke-linecap="round" />
  </svg>
</template>
