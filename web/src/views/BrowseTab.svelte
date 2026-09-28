<script lang="ts">
  import { Lock, RefreshCw } from '@lucide/svelte'
  import type { BrowseCard, BrowseView } from '../api'
  import { ago, givesBase } from '../money'
  import RequestCard from '../components/RequestCard.svelte'
  let { view, loading, pair = $bindable(), onTake, onRefresh }: {
    view: BrowseView; loading: boolean; pair: string | null; onTake: (c: BrowseCard) => void; onRefresh: () => void
  } = $props()
  const pairs = $derived([...new Set(view.cards.map((c) => `${c.base}/${c.quote}`))])
  const shown = $derived(view.cards.filter((c) => !pair || `${c.base}/${c.quote}` === pair))
</script>

<section class="flex flex-col gap-3.5">
  <div class="flex gap-2.5 items-start bg-base-100 rounded-box p-3.5 text-[13px] text-hint">
    <Lock size={18} class="shrink-0 mt-px" /><span>Nobody is named here. Tap Give and, if it fits, the bot introduces you both.</span>
  </div>
  <div class="flex items-center gap-1.5">
    <div class="flex gap-1.5 overflow-x-auto pb-0.5 flex-1 min-w-0">
      <button class={['shrink-0 text-[13px] px-3 py-1.5 rounded-full', !pair ? 'bg-primary text-primary-content font-semibold' : 'bg-base-100 text-hint']} onclick={() => (pair = null)}>All pairs</button>
      {#each pairs as k (k)}
        <button class={['shrink-0 text-[13px] px-3 py-1.5 rounded-full', pair === k ? 'bg-primary text-primary-content font-semibold' : 'bg-base-100 text-hint']}
                onclick={() => (pair = k)}>{k.replace('/', ' ⇄ ')}</button>
      {/each}
    </div>
    <button class="btn btn-ghost btn-circle btn-sm text-hint shrink-0" aria-label="Refresh" disabled={loading} onclick={onRefresh}>
      <RefreshCw size={18} class={loading ? 'animate-spin' : ''} />
    </button>
  </div>
  <div class="bg-base-100 rounded-box overflow-hidden">
    {#each shown as c, i (i)}
      <RequestCard side={givesBase(c.says, c.currency, c.base) ? 'give' : 'want'}
                   says={c.says} amount={c.amount} currency={c.currency} other={c.other} approxOther={c.approxOther}
                   meta={ago(c.createdAt)} action={`Give ${c.says === 'WANTS' ? c.currency : c.other}`} onAction={() => onTake(c)} />
    {/each}
    {#if !shown.length}<p class="p-3 text-sm text-hint">Nothing resting here right now.</p>{/if}
  </div>
</section>
