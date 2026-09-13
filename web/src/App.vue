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
      <!-- 全局提示/报错自动消失时长：2.2s 太短（报错常来不及读完），改 3s。
           个别重要提示仍可在调用处用 { duration: 4000|5000|6000 } 单独延长。 -->
      <NMessageProvider placement="top" :duration="3000" :max="3">
        <router-view />
      </NMessageProvider>
    </NDialogProvider>
  </NConfigProvider>
</template>
