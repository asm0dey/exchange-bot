<script setup lang="ts">
import { ArrowUpRight, Search, SlidersHorizontal } from 'lucide-vue-next'
const tab = defineModel<'mine' | 'browse' | 'tolerance'>({ required: true })
const tabs = [
  { key: 'mine', label: 'Mine', icon: ArrowUpRight },
  { key: 'browse', label: 'Browse', icon: Search },
  { key: 'tolerance', label: 'Tolerance', icon: SlidersHorizontal },
] as const
</script>
<template>
  <nav class="fixed inset-x-0 bottom-0 grid grid-cols-3 bg-base-100 border-t border-base-300 text-[11px] text-center text-hint pb-[env(safe-area-inset-bottom)]" role="tablist">
    <button v-for="t in tabs" :key="t.key" role="tab" :aria-selected="tab === t.key"
            class="flex flex-col items-center gap-0.5 pt-2 pb-3" :class="tab === t.key && 'text-primary font-semibold'" @click="tab = t.key">
      <component :is="t.icon" :size="22" />{{ t.label }}
    </button>
  </nav>
</template>
