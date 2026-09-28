<script lang="ts">
  import { onMount } from 'svelte'
  import { Plus, RefreshCw } from '@lucide/svelte'
  import { api, ApiError, type CardDto, type ChatView, type Says } from '../api'
  import { ago, givesBase, left } from '../money'
  import { haptic } from '../tg'
  import RequestCard from '../components/RequestCard.svelte'
  import RequestSheet from '../components/RequestSheet.svelte'

  let { chatId, onAuthLost }: { chatId: number; onAuthLost: () => void } = $props()

  let view = $state<ChatView | null>(null)
  let problem = $state<string | null>(null)
  let filter = $state<'all' | 'give' | 'want'>('all')
  let sheet = $state<null | { prefill: CardDto | null }>(null)
  let sheetError = $state<string | null>(null)
  let loading = $state(false)

  async function load() {
    loading = true
    try { view = await api.chat(chatId); problem = null }
    catch (e) {
      if (e instanceof ApiError && e.status === 401) onAuthLost()
      else problem = e instanceof ApiError && e.status === 403 ? "You're not in this chat." : 'Could not load. Retrying…'
    } finally { loading = false }
  }
  const onFocus = () => document.visibilityState === 'visible' && load()
  onMount(() => {
    load()
    const timer = window.setInterval(onFocus, 30_000)
    document.addEventListener('visibilitychange', onFocus)
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', onFocus) }
  })

  const rate = $derived(view?.rate ? Number(view.rate) : null)
  const side = (c: CardDto) => (givesBase(c.says, c.currency, view!.base) ? 'give' : 'want')
  const sections = $derived.by(() => {
    const v = view
    if (!v) return []
    const all = [
      { key: 'give', title: `Have ${v.base}, want ${v.quote}`, cards: v.cards.filter((c) => side(c) === 'give') },
      { key: 'want', title: `Have ${v.quote}, want ${v.base}`, cards: v.cards.filter((c) => side(c) === 'want') },
    ]
    return filter === 'all' ? all : all.filter((s) => s.key === filter)
  })
  /** What the viewer would hand over to take this card: its currency if they want it, else the other leg. */
  const giveLabel = (c: CardDto) => `Give ${c.says === 'WANTS' ? c.currency : c.other}`

  async function cancel(c: CardDto) {
    try { await api.cancel(c.token!); haptic('success'); await load() } catch (e) { problem = (e as Error).message; haptic('error') }
  }
  async function done(mineToken: string, peerShortId: string) {
    try { problem = (await api.done({ mineToken, peerShortId })).message; haptic('success'); await load() }
    catch (e) { problem = (e as Error).message; haptic('error') }
  }
  function openSheet(prefill: CardDto | null) { sheet = { prefill }; sheetError = null }
  function closeSheet() { sheet = null; sheetError = null }
  async function post(b: { says: Says; amount: string; currency: string }) {
    try { await api.post(chatId, b); haptic('success'); closeSheet(); await load() }
    catch (e) { sheetError = (e as Error).message; haptic('error') }
  }
</script>

<main class={['min-h-dvh flex flex-col gap-3.5 px-3 py-3.5 pb-24', sheet && 'overflow-hidden']}>
  {#if view}
    <header class="bg-base-100 rounded-box p-3.5 flex justify-between items-center gap-2.5">
      <div class="text-[22px] font-bold tracking-tight">{view.base}<span class="text-hint font-normal mx-1">⇄</span>{view.quote}</div>
      <div class="text-right text-xs text-hint">Reference, not a price
        {#if view.rate}
          <b class="block text-[15px] text-base-content amount">1 {view.base} = {view.rate} {view.quote}</b>
        {:else}
          <b class="block text-[13px] text-base-content">No rate right now</b>
        {/if}
      </div>
    </header>
    <div class="flex items-center gap-2">
      <div class="grid grid-cols-3 flex-1 bg-base-100 rounded-field p-[3px] text-[13px] text-center" role="tablist">
        {#each ['all', 'give', 'want'] as const as f (f)}
          <button role="tab" aria-selected={filter === f}
                  class={['py-1.5 rounded-lg', filter === f ? 'bg-base-200 font-semibold' : 'text-hint']} onclick={() => (filter = f)}>
            {f === 'all' ? 'All' : f === 'give' ? `Have ${view.base}` : `Want ${view.base}`}
          </button>
        {/each}
      </div>
      <button class="btn btn-ghost btn-circle btn-sm text-hint shrink-0" aria-label="Refresh" disabled={loading} onclick={load}>
        <RefreshCw size={18} class={loading ? 'animate-spin' : ''} />
      </button>
    </div>
    {#each sections as s (s.key)}
      <div class="flex justify-between text-xs uppercase tracking-wider text-hint px-1"><span>{s.title}</span><span>{s.cards.length}</span></div>
      <div class="bg-base-100 rounded-box overflow-hidden">
        {#each s.cards as c (c.shortId)}
          <RequestCard side={side(c)}
                       says={c.says} amount={c.amount} currency={c.currency} other={c.other} approxOther={c.approxOther}
                       name={c.mine ? null : c.name} mine={c.mine} meta={c.mine ? `${ago(c.createdAt)} · ${left(c.expiresAt)}` : ago(c.createdAt)}
                       action={c.mine ? 'Cancel' : giveLabel(c)}
                       onAction={() => (c.mine ? cancel(c) : openSheet(c))}>
            {#each c.counterparties as cp (cp.shortId)}
              <div class="flex justify-between items-center mt-2 text-sm">
                <span class="text-link">{cp.name}</span>
                <button class="btn btn-xs btn-outline btn-primary" onclick={() => done(cp.mineToken, cp.shortId)}>Done with</button>
              </div>
            {/each}
          </RequestCard>
        {/each}
        {#if !s.cards.length}<p class="p-3 text-sm text-hint">Nobody yet.</p>{/if}
      </div>
    {/each}
    {#if problem}<p class="text-sm text-hint" role="status">{problem}</p>{/if}
    <button class="btn btn-primary rounded-full fixed right-3 bottom-4 shadow-lg" onclick={() => openSheet(null)}><Plus size={16} /> New request</button>
  {:else}
    <p class="text-hint p-4">{problem ?? 'Loading…'}</p>
  {/if}

  {#if sheet && view}
    <!-- Tapping the dimmed backdrop closes the sheet; the keyboard path is Telegram's BackButton. -->
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="fixed inset-0 bg-black/45 flex flex-col justify-end" onclick={(e) => e.target === e.currentTarget && closeSheet()}>
      <RequestSheet base={view.base} quote={view.quote} {rate} destination={view.title ?? 'this chat'} error={sheetError}
                    prefill={sheet.prefill && { says: sheet.prefill.says, amount: sheet.prefill.amount, currency: sheet.prefill.currency, name: sheet.prefill.name, range: sheet.prefill.range }}
                    onSubmit={post} onClose={closeSheet} />
    </div>
  {/if}
</main>
