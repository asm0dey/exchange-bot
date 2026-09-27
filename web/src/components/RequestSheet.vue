<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeftRight } from 'lucide-vue-next'
import type { RangeDto, Says } from '../api'
import { allowedCurrencies, approx, fits, flip, fmt, matchCandidates, rangeIn, toBase, toTyped, type SheetCandidate } from '../money'
import { useBackButton, useMainButton } from '../tg'
import RangeBar from './RangeBar.vue'

const p = defineProps<{
  base: string; quote: string; rate: number | null; pairEditable?: boolean
  prefill?: { says: Says; amount: string; currency: string; name?: string | null; range?: RangeDto | null } | null
  candidates?: SheetCandidate[]
  destination: string; tolerancePct?: number | null; error?: string | null
  currencies?: string[]
}>()
const emit = defineEmits<{
  submit: [{ says: Says; amount: string; currency: string; other: string }]
  close: []
  pair: [{ base: string; quote: string }]
}>()

const says = ref<Says>(p.prefill ? flip(p.prefill.says) : 'GIVES')
const currency = ref(p.prefill?.currency ?? p.base)
const amount = ref(p.prefill?.amount ?? '')

const other = computed(() => (currency.value === p.base ? p.quote : p.base))
const n = computed(() => Number(amount.value.replace(/\s/g, '')))
const valid = computed(() => Number.isFinite(n.value) && n.value > 0)

const allowed = computed(() => allowedCurrencies(p.prefill ?? null, p.base, p.quote, says.value))
// Pre-filled: flipping the toggle re-expresses the same deal in the other leg.
// Use the sanitised n.value (amount.value may hold a thin-space-grouped display string);
// an invalid amount leaves the field empty rather than writing NaN into it.
watch(says, (now, before) => {
  if (!p.prefill || now === before) return
  const b = valid.value ? toBase(n.value, currency.value, p.base, p.rate) : null
  currency.value = allowed.value[0]
  const typed = b === null ? null : toTyped(b, currency.value, p.base, p.rate)
  amount.value = typed === null ? '' : String(Math.round(typed))
})
watch(allowed, (a) => { if (!a.includes(currency.value)) currency.value = a[0] })
const est = computed(() => {
  const b = toBase(n.value, currency.value, p.base, p.rate)
  const o = b === null ? null : toTyped(b, other.value, p.base, p.rate)
  return valid.value && o !== null ? approx(o) : null
})
// The prefill is always the exact card the user tapped, in its own base — never re-ordered by
// the pair picker (which only applies to a fresh, non-prefilled sheet) — so it reads p.base/p.rate
// directly. Free-typed candidates go through matchCandidates, keyed off each candidate's OWN base,
// so the result never depends on which way this sheet's own pair happens to be ordered.
const bands = computed(() => {
  if (p.prefill?.range) {
    const label = `${p.prefill.name ?? 'They'} match${p.prefill.name ? 'es' : ''}`
    const shown = rangeIn(p.prefill.range, currency.value, p.base, p.rate)
    if (shown === null) return []
    return [{ label, shown, ok: valid.value ? fits(n.value, currency.value, p.base, p.rate, p.prefill.range) : null }]
  }
  return matchCandidates(p.candidates ?? [], says.value, currency.value, valid.value ? n.value : null)
    .filter((b) => b.shown !== null)
})
const fitCount = computed(() => bands.value.filter((b) => b.ok).length)

const buttonText = computed(() => `Post in ${p.destination}`)
const submit = () => valid.value && emit('submit', { says: says.value, amount: String(n.value), currency: currency.value, other: other.value })
useMainButton(buttonText, valid, submit)
useBackButton(() => emit('close'))
</script>

<template>
  <section class="bg-base-100 rounded-t-2xl px-4 pt-2.5 pb-4 flex flex-col gap-3.5">
    <i class="w-9 h-1 rounded bg-base-300 self-center" />
    <h3 class="text-lg font-semibold">New request</h3>
    <p v-if="prefill" class="text-[13px] text-hint -mt-2">Filled in from {{ prefill.name ? `${prefill.name}'s` : 'this' }} request. Change anything before posting.</p>

    <div v-if="pairEditable" class="flex flex-col gap-1.5">
      <label class="text-xs uppercase tracking-wider text-hint" for="pair-base">Pair</label>
      <div class="grid grid-cols-[1fr_auto_1fr] gap-2 items-center">
        <select id="pair-base" class="select select-sm font-semibold" :value="base"
                @change="emit('pair', { base: ($event.target as HTMLSelectElement).value, quote })">
          <option v-for="c in currencies" :key="c">{{ c }}</option>
        </select>
        <button class="btn btn-circle btn-sm btn-ghost text-link" aria-label="Swap currencies"
                @click="emit('pair', { base: quote, quote: base })"><ArrowLeftRight :size="18" /></button>
        <select id="pair-quote" class="select select-sm font-semibold" :value="quote"
                @change="emit('pair', { base, quote: ($event.target as HTMLSelectElement).value })">
          <option v-for="c in currencies" :key="c">{{ c }}</option>
        </select>
      </div>
    </div>

    <div class="join w-full" role="radiogroup" aria-label="Direction">
      <button class="join-item btn btn-sm flex-1" :class="says === 'GIVES' ? 'btn-active' : 'btn-ghost'" role="radio" :aria-checked="says === 'GIVES'" @click="says = 'GIVES'">I give</button>
      <button class="join-item btn btn-sm flex-1" :class="says === 'WANTS' ? 'btn-active' : 'btn-ghost'" role="radio" :aria-checked="says === 'WANTS'" @click="says = 'WANTS'">{{ prefill ? 'I receive' : 'I want' }}</button>
    </div>

    <div class="flex flex-col gap-1.5">
      <label for="amount" class="text-xs uppercase tracking-wider text-hint">Amount</label>
      <label class="input w-full text-xl font-semibold amount">
        <input id="amount" v-model="amount" inputmode="decimal" class="grow" />
        <span class="text-hint text-base font-medium">{{ currency }}</span>
      </label>
      <div v-if="bands.length && !candidates?.length" class="flex flex-col gap-1.5 text-[13px]">
        <div v-for="b in bands" :key="b.label" class="flex justify-between">
          <span>{{ b.label }} <b class="amount">{{ fmt(b.shown!.min) }} – {{ b.shown!.max === null ? '…' : fmt(b.shown!.max) }} {{ currency }}</b></span>
          <span v-if="b.ok === true" class="text-want font-semibold">✓ fits</span>
          <span v-else-if="b.ok === false" class="text-hint">doesn't fit</span>
        </div>
        <RangeBar v-if="bands[0]" :min="bands[0].shown!.min" :max="bands[0].shown!.max" :value="valid ? n : null" />
      </div>
    </div>

    <div v-if="!prefill && !pairEditable" class="flex flex-col gap-1.5">
      <span class="text-xs uppercase tracking-wider text-hint">Currency</span>
      <div class="flex gap-2">
        <button v-for="c in allowed" :key="c" class="btn flex-1" :class="c === currency ? 'btn-active' : 'btn-ghost'" @click="currency = c">{{ c }}</button>
      </div>
    </div>
    <div v-else-if="prefill" class="flex flex-col gap-1.5">
      <span class="text-xs uppercase tracking-wider text-hint">Currency</span>
      <div class="flex gap-2">
        <button v-for="c in [base, quote]" :key="c" class="btn flex-1" :disabled="!allowed.includes(c)"
                :class="c === currency ? 'bg-give-soft text-give ring-[1.5px] ring-give' : 'btn-ghost'">{{ c }}</button>
      </div>
    </div>

    <div v-if="candidates?.length" class="flex flex-col gap-1.5">
      <span class="text-xs uppercase tracking-wider text-hint">{{ says === 'GIVES' ? 'Wanting' : 'Giving' }} {{ currency }} right now · fits {{ fitCount }} of {{ bands.length }}</span>
      <div v-for="b in bands" :key="b.label" class="bg-base-200 rounded-field px-3 py-2.5 flex flex-col gap-1.5 text-[13px]">
        <div class="flex justify-between gap-2">
          <span>{{ b.label }} · accepts <b class="amount">{{ fmt(b.shown!.min) }} – {{ b.shown!.max === null ? '…' : fmt(b.shown!.max) }}</b></span>
          <span v-if="b.ok" class="text-want font-semibold">✓ fits</span>
          <span v-else class="text-hint">{{ b.ok === false && n < b.shown!.min ? 'too small' : 'too big' }}</span>
        </div>
        <RangeBar :min="b.shown!.min" :max="b.shown!.max" :value="valid ? n : null" />
      </div>
    </div>

    <div class="bg-base-200 rounded-field px-3 py-2.5 text-[13px]">
      <template v-if="valid">You {{ says === 'GIVES' ? 'give' : 'receive' }} {{ fmt(n) }} {{ currency }}<template v-if="est"> for ≈ {{ est }} {{ other }}</template><template v-if="tolerancePct">, with your {{ tolerancePct }}% tolerance</template>.</template>
      <template v-else>Enter an amount.</template>
      <span class="block text-hint mt-0.5">≈ uses the reference rate; you two agree the real one.</span>
    </div>
    <p v-if="error" class="text-sm text-error" role="alert">{{ error }}</p>
  </section>
</template>
