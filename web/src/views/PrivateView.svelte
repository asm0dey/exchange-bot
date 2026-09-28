<script lang="ts">
  import { onMount } from 'svelte'
  import { Plus } from '@lucide/svelte'
  import { api, ApiError, type BrowseCard, type BrowseView, type MeView, type Says } from '../api'
  import { approx, fmt, toBase } from '../money'
  import { haptic } from '../tg'
  import RequestSheet from '../components/RequestSheet.svelte'
  import TabBar from '../components/TabBar.svelte'
  import BrowseTab from './BrowseTab.svelte'
  import MineTab from './MineTab.svelte'
  import ToleranceTab from './ToleranceTab.svelte'

  let { onAuthLost }: { onAuthLost: () => void } = $props()
  let tab = $state<'mine' | 'browse' | 'tolerance'>('mine')
  let me = $state<MeView | null>(null)
  let browse = $state<BrowseView | null>(null)
  let browsePair = $state<string | null>(null)
  let note = $state<string | null>(null)
  let sheet = $state<null | { base: string; quote: string; prefill: BrowseCard | null }>(null)
  let sheetError = $state<string | null>(null)
  let loading = $state(false)

  const fail = (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) return onAuthLost()
    note = (e as Error).message; haptic('error')
  }
  async function load() {
    loading = true
    try { [me, browse] = await Promise.all([api.me(), api.browse()]) } catch (e) { fail(e) } finally { loading = false }
  }
  const act = async (p: Promise<{ message: string }>) => {
    try { note = (await p).message; haptic('success'); await load() } catch (e) { fail(e) }
  }
  const onFocus = () => document.visibilityState === 'visible' && load()
  onMount(() => {
    load()
    const timer = window.setInterval(onFocus, 30_000)
    document.addEventListener('visibilitychange', onFocus)
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', onFocus) }
  })

  /** ISO codes the bot knows, offered in the pair picker: every pair already in play, plus the common ones. */
  const currencies = $derived.by(() => {
    const seen = new Set(['EUR', 'USD', 'RUB', 'RSD', 'GBP', 'CHF', 'TRY', 'GEL', 'AMD', 'KZT'])
    browse?.cards.forEach((c) => { seen.add(c.base); seen.add(c.quote) })
    me?.mine.forEach((m) => { seen.add(m.base); seen.add(m.quote) })
    return [...seen].sort()
  })
  const rateFor = (base: string, quote: string) => {
    const r = browse?.rates[`${base}/${quote}`] ?? null
    if (r) return Number(r)
    const inv = browse?.rates[`${quote}/${base}`] ?? null
    return inv ? 1 / Number(inv) : null
  }
  /** Same helper shape as GroupView: always clears any stale sheet error, whether opening or closing. */
  function openSheet(base: string, quote: string, prefill: BrowseCard | null) { sheet = { base, quote, prefill }; sheetError = null }
  function closeSheet() { sheet = null; sheetError = null }
  function openNew() {
    const [base, quote] = (browsePair ?? 'EUR/RUB').split('/')
    openSheet(base, quote, null)
  }
  /**
   * Everyone whose card matches this pair, carried with its own base/quote/rate so RequestSheet
   * can judge side and range against each candidate's OWN base — never this sheet's base, which
   * the pair picker and swap button can put on either side.
   */
  const candidates = $derived.by(() => {
    const s = sheet
    if (!s || s.prefill || !browse) return []
    return browse.cards
      .filter((c) => (c.base === s.base && c.quote === s.quote) || (c.base === s.quote && c.quote === s.base))
      .filter((c) => c.range)
      .map((c) => {
        const rate = rateFor(c.base, c.quote)
        // Only a candidate typed in the quote leg needs a base-currency equivalent alongside it —
        // one typed in the base already reads directly, and toBase is null without a rate anyway.
        const approxBase = c.currency !== c.base ? toBase(Number(c.amount), c.currency, c.base, rate) : null
        const approxText = approxBase === null ? '' : ` ≈ ${approx(approxBase)} ${c.base}`
        return {
          label: `${c.says === 'GIVES' ? 'Gives' : 'Wants'} ${fmt(Number(c.amount))} ${c.currency}${approxText}`,
          says: c.says, currency: c.currency, base: c.base, quote: c.quote,
          range: c.range!, rate,
        }
      })
  })
  async function submit(b: { says: Says; amount: string; currency: string; other: string }) {
    try { note = (await api.state(b)).message; haptic('success'); closeSheet(); tab = 'mine'; await load() }
    catch (e) { if (e instanceof ApiError && e.status === 401) onAuthLost(); else { sheetError = (e as Error).message; haptic('error') } }
  }
  async function saveTolerance(p: number) {
    try { me = await api.tolerance(p); haptic('success') } catch (e) { fail(e) }
  }
</script>

<main class="min-h-dvh flex flex-col gap-3.5 px-3 py-3.5 pb-28">
  {#if me && browse}
    {#if tab === 'mine'}
      <MineTab {me} {loading} onRefresh={load}
               onConfirm={(d, m) => act(api.confirm({ declarerToken: d, mineToken: m }))}
               onRefuse={(d, m) => act(api.refuse({ declarerToken: d, mineToken: m }))}
               onCancel={(t) => act(api.cancel(t))}
               onDone={(m, s) => act(api.done({ mineToken: m, peerShortId: s }))} />
    {:else if tab === 'browse'}
      <BrowseTab bind:pair={browsePair} view={browse} {loading} onRefresh={load}
                 onTake={(c) => openSheet(c.base, c.quote, c)} />
    {:else}
      <ToleranceTab pct={me.tolerancePct} error={null} onSave={saveTolerance} />
    {/if}
    {#if note}<p class="text-sm text-hint px-1" role="status">{note}</p>{/if}
    {#if tab !== 'tolerance'}
      <button class="btn btn-primary rounded-full fixed right-3 bottom-20 shadow-lg" onclick={openNew}><Plus size={16} /> New request</button>
    {/if}
  {:else}
    <p class="text-hint p-4">{note ?? 'Loading…'}</p>
  {/if}
  <TabBar bind:tab />

  {#if sheet}
    <!-- Tapping the dimmed backdrop closes the sheet; the keyboard path is Telegram's BackButton. -->
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="fixed inset-0 bg-black/45 flex flex-col justify-end z-10" onclick={(e) => e.target === e.currentTarget && closeSheet()}>
      {#key `${sheet.base}/${sheet.quote}/${!!sheet.prefill}`}
        <RequestSheet base={sheet.base} quote={sheet.quote} rate={rateFor(sheet.base, sheet.quote)}
                      pairEditable={!sheet.prefill} {currencies} {candidates}
                      prefill={sheet.prefill && { says: sheet.prefill.says, amount: sheet.prefill.amount, currency: sheet.prefill.currency, name: null, range: sheet.prefill.range }}
                      destination="all my chats" isPrivate tolerancePct={me?.tolerancePct} error={sheetError}
                      onPair={(p) => sheet && (sheet = { ...sheet, ...p })} onSubmit={submit} onClose={closeSheet} />
      {/key}
    </div>
  {/if}
</main>
