<script setup lang="ts">
import { NConfigProvider, NDialogProvider, NMessageProvider, darkTheme, zhCN, dateZhCN } from 'naive-ui'
import { themeOverrides } from '@/theme'
</script>

<template>
  <NConfigProvider
    :theme="darkTheme"
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
    inline-theme-disabled
  >
    <NDialogProvider>
      <!-- 全局提示/报错自动消失时长：2.2s → 5s（报错常来不及读完）。
           个别重要提示仍可在调用处用 { duration: ... } 单独调整。 -->
      <NMessageProvider placement="top" :duration="5000" :max="3">
        <router-view />
      </NMessageProvider>
    </NDialogProvider>
  </NConfigProvider>
</template>
