<script lang="ts">
  import type { Snippet } from 'svelte'
  import type { Says } from '../api'
  import { approx, fmt } from '../money'
  let { side, says, amount, currency, other, approxOther = null, meta, mine = false, name = null, action = null, onAction, children }: {
    side: 'give' | 'want'; says: Says; amount: string; currency: string; other: string; approxOther?: string | null
    meta: string; mine?: boolean; name?: string | null; action?: string | null; onAction?: () => void; children?: Snippet
  } = $props()
</script>

<!-- Amber stripe: has the base to give. Teal: wants the base. The words carry it too. -->
<div class="grid grid-cols-[4px_1fr_auto] gap-x-3 items-center py-3 pr-3 border-t border-base-300 first:border-t-0" data-side={side}>
  <i class={['self-stretch rounded-r', side === 'give' ? 'bg-give' : 'bg-want']}></i>
  <div class="min-w-0">
    <div class="amount font-semibold text-base">
      {says === 'GIVES' ? 'Gives' : 'Wants'} {fmt(Number(amount))} {currency}
      {#if mine}<span class="ml-1 text-[11px] font-normal bg-base-200 text-hint px-1.5 py-0.5 rounded">you</span>{/if}
    </div>
    <div class="text-[13px] text-hint">
      {#if approxOther}for ≈ {approx(Number(approxOther))} {other} · {/if}{#if name}<span class="text-link">{name}</span> · {/if}{meta}
    </div>
    {@render children?.()}
  </div>
  {#if action}
    <button class={['text-[13px] font-semibold whitespace-nowrap', mine ? 'text-hint font-medium' : 'text-link']} onclick={onAction}>{action}</button>
  {/if}
</div>
