<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Plus } from 'lucide-vue-next'
import { api, ApiError, type CardDto, type ChatView, type Says } from '../api'
import { givesBase } from '../money'
import { haptic } from '../tg'
import RequestCard from '../components/RequestCard.vue'
import RequestSheet from '../components/RequestSheet.vue'

const props = defineProps<{ chatId: number }>()
const emit = defineEmits<{ authLost: [] }>()

const view = ref<ChatView | null>(null)
const problem = ref<string | null>(null)
const filter = ref<'all' | 'give' | 'want'>('all')
const sheet = ref<null | { prefill: CardDto | null }>(null)
const sheetError = ref<string | null>(null)

async function load() {
  try { view.value = await api.chat(props.chatId); problem.value = null }
  catch (e) {
    if (e instanceof ApiError && e.status === 401) emit('authLost')
    else problem.value = e instanceof ApiError && e.status === 403 ? "You're not in this chat." : 'Could not load. Pull to retry.'
  }
}
let timer: number | undefined
const onFocus = () => document.visibilityState === 'visible' && load()
onMounted(() => { load(); timer = window.setInterval(onFocus, 30_000); document.addEventListener('visibilitychange', onFocus) })
onUnmounted(() => { clearInterval(timer); document.removeEventListener('visibilitychange', onFocus) })

const rate = computed(() => (view.value?.rate ? Number(view.value.rate) : null))
const side = (c: CardDto) => (givesBase(c.says, c.currency, view.value!.base) ? 'give' : 'want')
const sections = computed(() => {
  const v = view.value
  if (!v) return []
  const all = [
    { key: 'give', title: `Have ${v.base}, want ${v.quote}`, cards: v.cards.filter((c) => side(c) === 'give') },
    { key: 'want', title: `Have ${v.quote}, want ${v.base}`, cards: v.cards.filter((c) => side(c) === 'want') },
  ]
  return filter.value === 'all' ? all : all.filter((s) => s.key === filter.value)
})
const ago = (s: number) => { const h = Math.floor((Date.now() / 1000 - s) / 3600); return h < 24 ? `${Math.max(h, 1)} h` : `${Math.floor(h / 24)} days` }
const left = (s: number) => `${Math.max(0, Math.ceil((s - Date.now() / 1000) / 86400))} days left`
/** What the viewer would hand over to take this card: its currency if they want it, else the other leg. */
const giveLabel = (c: CardDto) => `Give ${c.says === 'WANTS' ? c.currency : c.other}`

async function cancel(c: CardDto) {
  try { await api.cancel(c.token!); haptic('success'); await load() } catch (e) { problem.value = (e as Error).message; haptic('error') }
}
async function done(mineToken: string, peerShortId: string) {
  try { problem.value = (await api.done({ mineToken, peerShortId })).message; haptic('success'); await load() }
  catch (e) { problem.value = (e as Error).message; haptic('error') }
}
function openSheet(prefill: CardDto | null) { sheet.value = { prefill }; sheetError.value = null }
function closeSheet() { sheet.value = null; sheetError.value = null }
async function post(b: { says: Says; amount: string; currency: string }) {
  try { await api.post(props.chatId, b); haptic('success'); closeSheet(); await load() }
  catch (e) { sheetError.value = (e as Error).message; haptic('error') }
}
</script>

<template>
  <main class="min-h-dvh flex flex-col gap-3.5 px-3 py-3.5 pb-24" :class="sheet && 'overflow-hidden'">
    <template v-if="view">
      <header class="bg-base-100 rounded-box p-3.5 flex justify-between items-center gap-2.5">
        <div class="text-[22px] font-bold tracking-tight">{{ view.base }}<span class="text-hint font-normal mx-1">⇄</span>{{ view.quote }}</div>
        <div class="text-right text-xs text-hint">Reference, not a price
          <b v-if="view.rate" class="block text-[15px] text-base-content amount">1 {{ view.base }} = {{ view.rate }} {{ view.quote }}</b>
          <b v-else class="block text-[13px] text-base-content">No rate right now</b>
        </div>
      </header>
      <div class="grid grid-cols-3 bg-base-100 rounded-field p-[3px] text-[13px] text-center" role="tablist">
        <button v-for="f in (['all', 'give', 'want'] as const)" :key="f" role="tab" :aria-selected="filter === f"
                class="py-1.5 rounded-lg" :class="filter === f ? 'bg-base-200 font-semibold' : 'text-hint'" @click="filter = f">
          {{ f === 'all' ? 'All' : f === 'give' ? `Have ${view.base}` : `Want ${view.base}` }}
        </button>
      </div>
      <template v-for="s in sections" :key="s.key">
        <div class="flex justify-between text-xs uppercase tracking-wider text-hint px-1"><span>{{ s.title }}</span><span>{{ s.cards.length }}</span></div>
        <div class="bg-base-100 rounded-box overflow-hidden">
          <RequestCard v-for="c in s.cards" :key="c.shortId" :data-side="side(c)"
                       :says="c.says" :amount="c.amount" :currency="c.currency" :other="c.other" :approx-other="c.approxOther"
                       :name="c.mine ? null : c.name" :mine="c.mine" :meta="c.mine ? `${ago(c.createdAt)} · ${left(c.expiresAt)}` : ago(c.createdAt)"
                       :action="c.mine ? 'Cancel' : giveLabel(c)"
                       @action="c.mine ? cancel(c) : openSheet(c)">
            <div v-for="cp in c.counterparties" :key="cp.shortId" class="flex justify-between items-center mt-2 text-sm">
              <span class="text-link">{{ cp.name }}</span>
              <button class="btn btn-xs btn-outline btn-primary" @click="done(cp.mineToken, cp.shortId)">Done with</button>
            </div>
          </RequestCard>
          <p v-if="!s.cards.length" class="p-3 text-sm text-hint">Nobody yet.</p>
        </div>
      </template>
      <p v-if="problem" class="text-sm text-hint" role="status">{{ problem }}</p>
      <button class="btn btn-primary rounded-full fixed right-3 bottom-4 shadow-lg" @click="openSheet(null)"><Plus :size="16" /> New request</button>
    </template>
    <p v-else class="text-hint p-4">{{ problem ?? 'Loading…' }}</p>

    <div v-if="sheet && view" class="fixed inset-0 bg-black/45 flex flex-col justify-end" @click.self="closeSheet">
      <RequestSheet :base="view.base" :quote="view.quote" :rate="rate" :destination="view.title ?? 'this chat'" :error="sheetError"
                    :prefill="sheet.prefill && { says: sheet.prefill.says, amount: sheet.prefill.amount, currency: sheet.prefill.currency, name: sheet.prefill.name, range: sheet.prefill.range }"
                    @submit="post" @close="closeSheet" />
    </div>
  </main>
</template>
