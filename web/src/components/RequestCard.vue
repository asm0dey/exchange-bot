<script setup lang="ts">
import type { Says } from '../api'
import { approx, fmt } from '../money'
defineProps<{
  says: Says; amount: string; currency: string; other: string; approxOther?: string | null
  meta: string; mine?: boolean; name?: string | null; action?: string | null
}>()
defineEmits<{ action: [] }>()
</script>
<template>
  <!-- Amber stripe: has the base to give. Teal: wants the base. The words carry it too. -->
  <div class="grid grid-cols-[4px_1fr_auto] gap-x-3 items-center py-3 pr-3 border-t border-base-300 first:border-t-0">
    <i class="self-stretch rounded-r" :class="$attrs['data-side'] === 'give' ? 'bg-give' : 'bg-want'" />
    <div class="min-w-0">
      <div class="amount font-semibold text-base">
        {{ says === 'GIVES' ? 'Gives' : 'Wants' }} {{ fmt(Number(amount)) }} {{ currency }}
        <span v-if="mine" class="ml-1 text-[11px] font-normal bg-base-200 text-hint px-1.5 py-0.5 rounded">you</span>
      </div>
      <div class="text-[13px] text-hint">
        <template v-if="approxOther">for ≈ {{ approx(Number(approxOther)) }} {{ other }} · </template>
        <span v-if="name" class="text-link">{{ name }}</span><template v-if="name"> · </template>{{ meta }}
      </div>
      <slot />
    </div>
    <button v-if="action" class="text-[13px] font-semibold whitespace-nowrap"
            :class="mine ? 'text-hint font-medium' : 'text-link'" @click="$emit('action')">{{ action }}</button>
  </div>
</template>
