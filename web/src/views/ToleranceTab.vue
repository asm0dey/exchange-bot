<script setup lang="ts">
import { ref, watch } from 'vue'
const p = defineProps<{ pct: number; error: string | null }>()
const emit = defineEmits<{ save: [number] }>()
const value = ref(p.pct)
watch(() => p.pct, (v) => { value.value = v })
</script>
<template>
  <section class="bg-base-100 rounded-box p-4 flex flex-col gap-3">
    <label for="tol" class="font-semibold">Size tolerance: <span class="amount">{{ value }}%</span></label>
    <input id="tol" v-model.number="value" type="range" min="1" max="100" class="range range-primary range-sm" />
    <p class="text-[13px] text-hint">A counterparty matches when what they'd leave you is within {{ value }}% of your own amount. It applies to requests you post here, never to a group's own setting.</p>
    <button class="btn btn-primary" :disabled="value === pct" @click="emit('save', value)">Save</button>
    <p v-if="error" class="text-sm text-error" role="alert">{{ error }}</p>
  </section>
</template>
