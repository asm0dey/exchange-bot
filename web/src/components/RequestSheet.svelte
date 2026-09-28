<script lang="ts">
  import { untrack } from 'svelte'
  import { ArrowLeftRight } from '@lucide/svelte'
  import type { RangeDto, Says } from '../api'
  import { allowedCurrencies, approx, fits, flip, fmt, matchCandidates, parseAmount, rangeIn, toBase, toTyped, type SheetCandidate } from '../money'
  import { backButton, mainButton, syncMainButton } from '../tg'
  import RangeBar from './RangeBar.svelte'

  let { base, quote, rate, pairEditable = false, prefill = null, candidates, destination, tolerancePct = null, error = null,
        currencies = [], isPrivate = false, onSubmit, onClose, onPair }: {
    base: string; quote: string; rate: number | null; pairEditable?: boolean
    prefill?: { says: Says; amount: string; currency: string; name?: string | null; range?: RangeDto | null } | null
    candidates?: SheetCandidate[]
    destination: string; tolerancePct?: number | null; error?: string | null
    currencies?: string[]
    /** Private (bot-wide) vs. a specific group chat: only changes the summary's second line. */
    isPrivate?: boolean
    onSubmit: (b: { says: Says; amount: string; currency: string; other: string }) => void
    onClose: () => void
    onPair?: (p: { base: string; quote: string }) => void
  } = $props()

  // Seeded once from the props: the parents remount the sheet (`{#key}` / `{#if}`) whenever
  // the pair or the prefill changes, so these never need to follow the props afterwards.
  let says = $state<Says>(untrack(() => (prefill ? flip(prefill.says) : 'GIVES')))
  let currency = $state(untrack(() => prefill?.currency ?? base))
  let amount = $state(untrack(() => prefill?.amount ?? ''))

  const other = $derived(currency === base ? quote : base)
  // parseAmount mirrors the bot's own comma/space handling (Money.kt's parseAmount) — a
  // comma-grouped amount like "1,000" must be accepted here exactly as it would be server-side.
  const n = $derived(parseAmount(amount) ?? NaN)
  const valid = $derived(Number.isFinite(n) && n > 0)

  const allowed = $derived(allowedCurrencies(prefill, base, quote, says))

  /**
   * The direction toggle. `allowed` depends only on `says` (base, quote and prefill are fixed for
   * the sheet's lifetime), so everything that has to follow a flip happens here, not in an effect.
   */
  function setSays(next: Says) {
    if (next === says) return
    // Read in the old direction's currency, before `allowed` moves.
    const b = prefill && valid ? toBase(n, currency, base, rate) : null
    says = next
    if (!prefill) {
      if (!allowed.includes(currency)) currency = allowed[0]
      return
    }
    // Pre-filled: the same deal, re-expressed in the other leg. No amount or no rate leaves the
    // field empty rather than writing NaN into it.
    currency = allowed[0]
    const typed = b === null ? null : toTyped(b, currency, base, rate)
    amount = typed === null ? '' : String(Math.round(typed))
  }

  const est = $derived.by(() => {
    const b = toBase(n, currency, base, rate)
    const o = b === null ? null : toTyped(b, other, base, rate)
    return valid && o !== null ? approx(o) : null
  })
  // The prefill is always the exact card the user tapped, in its own base — never re-ordered by
  // the pair picker (which only applies to a fresh, non-prefilled sheet) — so it reads base/rate
  // directly. Free-typed candidates go through matchCandidates, keyed off each candidate's OWN base,
  // so the result never depends on which way this sheet's own pair happens to be ordered.
  const bands = $derived.by(() => {
    if (prefill?.range) {
      const label = `${prefill.name ?? 'They'} match${prefill.name ? 'es' : ''}`
      const shown = rangeIn(prefill.range, currency, base, rate)
      if (shown === null) return []
      return [{ label, shown, ok: valid ? fits(n, currency, base, rate, prefill.range) : null }]
    }
    return matchCandidates(candidates ?? [], says, currency, valid ? n : null)
      .filter((b) => b.shown !== null)
  })
  const fitCount = $derived(bands.filter((b) => b.ok).length)

  const submit = () => valid && onSubmit({ says, amount: String(n), currency, other })
  // Declared before mainButton so the text is set before the button is first shown.
  $effect(() => syncMainButton(`Post in ${destination}`, valid))
  $effect(() => mainButton(submit))
  $effect(() => backButton(onClose))
</script>

<section class="bg-base-100 rounded-t-2xl px-4 pt-2.5 pb-4 flex flex-col gap-3.5">
  <i class="w-9 h-1 rounded bg-base-300 self-center"></i>
  <h3 class="text-lg font-semibold">New request</h3>
  {#if prefill}<p class="text-[13px] text-hint -mt-2">Filled in from {prefill.name ? `${prefill.name}'s` : 'this'} request. Change anything before posting.</p>{/if}

  {#if pairEditable}
    <div class="flex flex-col gap-1.5">
      <label class="text-xs uppercase tracking-wider text-hint" for="pair-base">Pair</label>
      <div class="grid grid-cols-[1fr_auto_1fr] gap-2 items-center">
        <select id="pair-base" class="select w-full bg-base-200 border-0 font-semibold" value={base}
                onchange={(e) => onPair?.({ base: e.currentTarget.value, quote })}>
          {#each currencies as c (c)}<option value={c}>{c}</option>{/each}
        </select>
        <button class="rounded-full bg-base-200 text-link w-[34px] h-[34px] shrink-0 grid place-items-center" aria-label="Swap currencies"
                onclick={() => onPair?.({ base: quote, quote: base })}><ArrowLeftRight size={18} /></button>
        <select id="pair-quote" class="select w-full bg-base-200 border-0 font-semibold" value={quote}
                onchange={(e) => onPair?.({ base, quote: e.currentTarget.value })}>
          {#each currencies as c (c)}<option value={c}>{c}</option>{/each}
        </select>
      </div>
    </div>
  {/if}

  <div class="join w-full" role="radiogroup" aria-label="Direction">
    <button class={['join-item btn btn-sm flex-1', says === 'GIVES' ? 'btn-active' : 'btn-ghost']} role="radio" aria-checked={says === 'GIVES'} onclick={() => setSays('GIVES')}>I give</button>
    <button class={['join-item btn btn-sm flex-1', says === 'WANTS' ? 'btn-active' : 'btn-ghost']} role="radio" aria-checked={says === 'WANTS'} onclick={() => setSays('WANTS')}>{prefill ? 'I receive' : 'I want'}</button>
  </div>

  <div class="flex flex-col gap-1.5">
    <label for="amount" class="text-xs uppercase tracking-wider text-hint">Amount</label>
    <label class="flex items-center justify-between gap-2 w-full bg-base-200 rounded-field px-3.5 py-3 text-xl font-semibold amount">
      <input id="amount" bind:value={amount} inputmode="decimal" class="grow bg-transparent outline-none" />
      <span class="text-hint text-base font-medium">{currency}</span>
    </label>
    {#if bands.length && !candidates?.length}
      <div class="flex flex-col gap-1.5 text-[13px]">
        {#each bands as b (b.label)}
          <div class="flex justify-between">
            <span>{b.label} <b class="amount">{fmt(b.shown!.min)} – {b.shown!.max === null ? '…' : fmt(b.shown!.max)} {currency}</b></span>
            {#if b.ok === true}<span class="text-want font-semibold">✓ fits</span>
            {:else if b.ok === false}<span class="text-hint">doesn't fit</span>{/if}
          </div>
        {/each}
        {#if bands[0]}<RangeBar min={bands[0].shown!.min} max={bands[0].shown!.max} value={valid ? n : null} />{/if}
      </div>
    {/if}
  </div>

  {#if !prefill && !pairEditable}
    <div class="flex flex-col gap-1.5">
      <span class="text-xs uppercase tracking-wider text-hint">Currency</span>
      <div class="flex gap-2">
        {#each allowed as c (c)}
          <button class={['btn flex-1', c === currency ? 'btn-active' : 'btn-ghost']} onclick={() => (currency = c)}>{c}</button>
        {/each}
      </div>
    </div>
  {:else if prefill}
    <div class="flex flex-col gap-1.5">
      <span class="text-xs uppercase tracking-wider text-hint">Currency</span>
      <div class="flex gap-2">
        {#each [base, quote] as c (c)}
          <button class={['btn flex-1', c === currency ? 'bg-give-soft text-give ring-[1.5px] ring-give' : '!bg-base-200 !text-hint !border-transparent']}
                  disabled={!allowed.includes(c)}>{c}</button>
        {/each}
      </div>
    </div>
  {/if}

  {#if candidates?.length}
    <div class="flex flex-col gap-1.5">
      <span class="text-xs uppercase tracking-wider text-hint">{says === 'GIVES' ? 'Wanting' : 'Giving'} {currency} right now · fits {fitCount} of {bands.length}</span>
      {#each bands as b (b.label)}
        <div class="bg-base-200 rounded-field px-3 py-2.5 flex flex-col gap-1.5 text-[13px]">
          <div class="flex justify-between gap-2">
            <span>{b.label} · accepts <b class="amount">{fmt(b.shown!.min)} – {b.shown!.max === null ? '…' : fmt(b.shown!.max)}</b></span>
            {#if b.ok === true}<span class="text-want font-semibold">✓ fits</span>
            {:else if b.ok === false}<span class="text-hint">{n < b.shown!.min ? 'too small' : 'too big'}</span>{/if}
          </div>
          <RangeBar min={b.shown!.min} max={b.shown!.max} value={valid ? n : null} />
        </div>
      {/each}
    </div>
  {/if}

  <div class="bg-base-200 rounded-field px-3 py-2.5 text-[13px]">
    {#if valid}You {says === 'GIVES' ? 'give' : 'receive'} {fmt(n)} {currency}{#if est} for ≈ {est} {other}{/if}{#if tolerancePct}, with your {tolerancePct}% tolerance{/if}.{:else}Enter an amount.{/if}
    {#if isPrivate}
      <span class="block text-hint mt-0.5">Shown in your chats that allow it, and matched with anyone privately. Names pass only on a match.</span>
    {:else}
      <span class="block text-hint mt-0.5">Posted in {destination}. ≈ uses the reference rate; you two agree the real one.</span>
    {/if}
  </div>
  {#if error}<p class="text-sm text-error" role="alert">{error}</p>{/if}
</section>
