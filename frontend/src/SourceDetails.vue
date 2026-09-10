<script setup lang="ts">
import type { Provenance } from './api'
defineProps<{ docId: string; provenance?: Provenance }>()
</script>

<template>
  <div class="provenance-card">
    <div>TSB编号：{{ docId }}</div>
    <div>章节：{{ provenance?.locator || '暂无章节信息' }}</div>
    <div>适用范围：{{ provenance?.applicability || '请核对车型、年份与实际车辆状态' }}</div>
  </div>
</template>
