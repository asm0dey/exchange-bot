<script setup lang="ts">
import { computed } from 'vue'
const p = defineProps<{ min: number; max: number | null; value: number | null }>()
/** Scale: from 0 to 1.6× the range's top (or 2× its floor when open-ended). */
const top = computed(() => (p.max ?? p.min * 2) * 1.6)
const pct = (n: number) => `${Math.max(0, Math.min(100, (n / top.value) * 100))}%`
</script>
<template>
  <div class="relative h-[18px]" aria-hidden="true">
    <i class="absolute inset-x-0 top-2 h-0.5 rounded bg-base-300" />
    <i class="absolute top-[5px] h-2 rounded bg-want-soft ring-[1.5px] ring-want ring-inset"
       :style="{ left: pct(min), width: max === null ? `calc(100% - ${pct(min)})` : `calc(${pct(max)} - ${pct(min)})` }" />
    <i v-if="value !== null" class="absolute top-px h-4 w-[3px] -ml-px rounded bg-base-content" :style="{ left: pct(value) }" />
  </div>
</template>
