<script setup lang="ts">
import { computed } from 'vue'
import { Lock, RefreshCw } from 'lucide-vue-next'
import type { BrowseCard, BrowseView } from '../api'
import { ago, givesBase } from '../money'
import RequestCard from '../components/RequestCard.vue'
const p = defineProps<{ view: BrowseView; loading: boolean }>()
const pair = defineModel<string | null>('pair', { required: true })
defineEmits<{ take: [BrowseCard]; refresh: [] }>()
const pairs = computed(() => [...new Set(p.view.cards.map((c) => `${c.base}/${c.quote}`))])
const shown = computed(() => p.view.cards.filter((c) => !pair.value || `${c.base}/${c.quote}` === pair.value))
</script>
<template>
  <section class="flex flex-col gap-3.5">
    <div class="flex gap-2.5 items-start bg-base-100 rounded-box p-3.5 text-[13px] text-hint">
      <Lock :size="18" class="shrink-0 mt-px" /><span>Nobody is named here. Tap Give and, if it fits, the bot introduces you both.</span>
    </div>
    <div class="flex items-center gap-1.5">
      <div class="flex gap-1.5 overflow-x-auto pb-0.5 flex-1 min-w-0">
        <button class="shrink-0 text-[13px] px-3 py-1.5 rounded-full" :class="!pair ? 'bg-primary text-primary-content font-semibold' : 'bg-base-100 text-hint'" @click="pair = null">All pairs</button>
        <button v-for="k in pairs" :key="k" class="shrink-0 text-[13px] px-3 py-1.5 rounded-full"
                :class="pair === k ? 'bg-primary text-primary-content font-semibold' : 'bg-base-100 text-hint'" @click="pair = k">{{ k.replace('/', ' ⇄ ') }}</button>
      </div>
      <button class="btn btn-ghost btn-circle btn-sm text-hint shrink-0" aria-label="Refresh" :disabled="loading" @click="$emit('refresh')">
        <RefreshCw :size="18" :class="loading && 'animate-spin'" />
      </button>
    </div>
    <div class="bg-base-100 rounded-box overflow-hidden">
      <RequestCard v-for="(c, i) in shown" :key="i" :data-side="givesBase(c.says, c.currency, c.base) ? 'give' : 'want'"
                   :says="c.says" :amount="c.amount" :currency="c.currency" :other="c.other" :approx-other="c.approxOther"
                   :meta="ago(c.createdAt)" :action="`Give ${c.says === 'WANTS' ? c.currency : c.other}`" @action="$emit('take', c)" />
      <p v-if="!shown.length" class="p-3 text-sm text-hint">Nothing resting here right now.</p>
    </div>
  </section>
</template>
