<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Plus } from 'lucide-vue-next'
import { api, ApiError, type BrowseCard, type BrowseView, type MeView, type Says } from '../api'
import { haptic } from '../tg'
import RequestSheet from '../components/RequestSheet.vue'
import TabBar from '../components/TabBar.vue'
import BrowseTab from './BrowseTab.vue'
import MineTab from './MineTab.vue'
import ToleranceTab from './ToleranceTab.vue'

const emit = defineEmits<{ authLost: [] }>()
const tab = ref<'mine' | 'browse' | 'tolerance'>('mine')
const me = ref<MeView | null>(null)
const browse = ref<BrowseView | null>(null)
const browsePair = ref<string | null>(null)
const note = ref<string | null>(null)
const sheet = ref<null | { base: string; quote: string; prefill: BrowseCard | null }>(null)
const sheetError = ref<string | null>(null)

const fail = (e: unknown) => {
  if (e instanceof ApiError && e.status === 401) return emit('authLost')
  note.value = (e as Error).message; haptic('error')
}
async function load() {
  try { [me.value, browse.value] = await Promise.all([api.me(), api.browse()]) } catch (e) { fail(e) }
}
const act = async (p: Promise<{ message: string }>) => {
  try { note.value = (await p).message; haptic('success'); await load() } catch (e) { fail(e) }
}
let timer: number | undefined
const onFocus = () => document.visibilityState === 'visible' && load()
onMounted(() => { load(); timer = window.setInterval(onFocus, 30_000); document.addEventListener('visibilitychange', onFocus) })
onUnmounted(() => { clearInterval(timer); document.removeEventListener('visibilitychange', onFocus) })

/** ISO codes the bot knows, offered in the pair picker: every pair already in play, plus the common ones. */
const currencies = computed(() => {
  const seen = new Set(['EUR', 'USD', 'RUB', 'RSD', 'GBP', 'CHF', 'TRY', 'GEL', 'AMD', 'KZT'])
  browse.value?.cards.forEach((c) => { seen.add(c.base); seen.add(c.quote) })
  me.value?.mine.forEach((m) => { seen.add(m.base); seen.add(m.quote) })
  return [...seen].sort()
})
const rateFor = (base: string, quote: string) => {
  const r = browse.value?.rates[`${base}/${quote}`] ?? null
  if (r) return Number(r)
  const inv = browse.value?.rates[`${quote}/${base}`] ?? null
  return inv ? 1 / Number(inv) : null
}
/** Same helper shape as GroupView: always clears any stale sheet error, whether opening or closing. */
function openSheet(base: string, quote: string, prefill: BrowseCard | null) { sheet.value = { base, quote, prefill }; sheetError.value = null }
function closeSheet() { sheet.value = null; sheetError.value = null }
function openNew() {
  const [base, quote] = (browsePair.value ?? 'EUR/RUB').split('/')
  openSheet(base, quote, null)
}
/**
 * Everyone whose card matches this pair, carried with its own base/quote/rate so RequestSheet
 * can judge side and range against each candidate's OWN base — never this sheet's base, which
 * the pair picker and swap button can put on either side.
 */
const candidates = computed(() => {
  const s = sheet.value
  if (!s || s.prefill || !browse.value) return []
  return browse.value.cards
    .filter((c) => (c.base === s.base && c.quote === s.quote) || (c.base === s.quote && c.quote === s.base))
    .filter((c) => c.range)
    .map((c) => ({
      label: `${c.says === 'GIVES' ? 'Gives' : 'Wants'} ${c.amount} ${c.currency}`,
      says: c.says, currency: c.currency, base: c.base, quote: c.quote,
      range: c.range!, rate: rateFor(c.base, c.quote),
    }))
})
async function submit(b: { says: Says; amount: string; currency: string; other: string }) {
  try { note.value = (await api.state(b)).message; haptic('success'); closeSheet(); tab.value = 'mine'; await load() }
  catch (e) { if (e instanceof ApiError && e.status === 401) emit('authLost'); else { sheetError.value = (e as Error).message; haptic('error') } }
}
</script>

<template>
  <main class="min-h-dvh flex flex-col gap-3.5 px-3 py-3.5 pb-28">
    <template v-if="me && browse">
      <MineTab v-if="tab === 'mine'" :me="me"
               @confirm="(d, m) => act(api.confirm({ declarerToken: d, mineToken: m }))"
               @refuse="(d, m) => act(api.refuse({ declarerToken: d, mineToken: m }))"
               @cancel="(t) => act(api.cancel(t))"
               @done="(m, s) => act(api.done({ mineToken: m, peerShortId: s }))" />
      <BrowseTab v-else-if="tab === 'browse'" v-model:pair="browsePair" :view="browse"
                 @take="(c) => openSheet(c.base, c.quote, c)" />
      <ToleranceTab v-else :pct="me.tolerancePct" :error="null"
                    @save="async (p) => { try { me = await api.tolerance(p); haptic('success') } catch (e) { fail(e) } }" />
      <p v-if="note" class="text-sm text-hint px-1" role="status">{{ note }}</p>
      <button v-if="tab !== 'tolerance'" class="btn btn-primary rounded-full fixed right-3 bottom-20 shadow-lg" @click="openNew"><Plus :size="16" /> New request</button>
    </template>
    <p v-else class="text-hint p-4">{{ note ?? 'Loading…' }}</p>
    <TabBar v-model="tab" />

    <div v-if="sheet" class="fixed inset-0 bg-black/45 flex flex-col justify-end z-10" @click.self="closeSheet">
      <RequestSheet :key="`${sheet.base}/${sheet.quote}/${!!sheet.prefill}`" :base="sheet.base" :quote="sheet.quote" :rate="rateFor(sheet.base, sheet.quote)"
                    :pair-editable="!sheet.prefill" :currencies="currencies" :candidates="candidates"
                    :prefill="sheet.prefill && { says: sheet.prefill.says, amount: sheet.prefill.amount, currency: sheet.prefill.currency, name: null, range: sheet.prefill.range }"
                    destination="all my chats" :tolerance-pct="me?.tolerancePct" :error="sheetError"
                    @pair="(p) => sheet && (sheet = { ...sheet, ...p })" @submit="submit" @close="closeSheet" />
    </div>
  </main>
</template>
