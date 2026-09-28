<script lang="ts">
  import { RefreshCw } from '@lucide/svelte'
  import type { MeView } from '../api'
  import { approx, fmt } from '../money'
  let { me, loading, onConfirm, onRefuse, onCancel, onDone, onRefresh }: {
    me: MeView; loading: boolean
    onConfirm: (declarerToken: string, mineToken: string) => void; onRefuse: (declarerToken: string, mineToken: string) => void
    onCancel: (token: string) => void; onDone: (mineToken: string, peerShortId: string) => void; onRefresh: () => void
  } = $props()
</script>

<section class="flex flex-col gap-3.5">
  {#each me.pending as p (p.declarerToken + p.mineToken)}
    <div class="bg-[color-mix(in_srgb,var(--color-give-soft)_70%,transparent)] rounded-box p-3.5 flex flex-col gap-2.5">
      <p class="text-sm"><b>{p.name}</b> says you two swapped <b class="amount">{fmt(Number(p.amount))} {p.currency} ⇄ {p.other}</b>. Did it happen?</p>
      <div class="flex gap-2">
        <button class="btn btn-primary btn-sm flex-1" onclick={() => onConfirm(p.declarerToken, p.mineToken)}>Yes, we swapped</button>
        <button class="btn btn-sm flex-1 bg-base-100" onclick={() => onRefuse(p.declarerToken, p.mineToken)}>No</button>
      </div>
    </div>
  {/each}

  <div class="flex justify-between items-center text-xs uppercase tracking-wider text-hint px-1">
    <span>Your requests</span>
    <div class="flex items-center gap-1">
      <span>{me.mine.length} of {me.limit}</span>
      <button class="btn btn-ghost btn-circle btn-xs text-hint" aria-label="Refresh" disabled={loading} onclick={onRefresh}>
        <RefreshCw size={14} class={loading ? 'animate-spin' : ''} />
      </button>
    </div>
  </div>
  {#each me.mine as m (m.token)}
    <article class="bg-base-100 rounded-box p-3.5 flex flex-col gap-2.5">
      <div class="flex justify-between items-baseline gap-2">
        <div><span class="amount font-semibold">{m.says === 'GIVES' ? 'Gives' : 'Wants'} {fmt(Number(m.amount))} {m.currency}</span>
          {#if m.approxOther}<span class="text-[13px] text-hint"> for ≈ {approx(Number(m.approxOther))} {m.other}</span>{/if}</div>
        <button class="text-[13px] text-hint" onclick={() => onCancel(m.token)}>Cancel</button>
      </div>
      <div class="flex flex-wrap gap-1.5">
        {#each m.chats as c (c.id)}
          <span class="text-xs bg-base-200 text-hint px-2 py-0.5 rounded">{c.title ?? 'A group'}</span>
        {/each}
        <span class="text-xs bg-base-200 text-hint px-2 py-0.5 rounded">No chat · bot-wide</span>
      </div>
      <div class="border-t border-base-300 pt-2 flex flex-col gap-2">
        {#each m.counterparties as cp (cp.mineToken + cp.shortId)}
          <div class="flex justify-between items-center gap-2 text-sm">
            <div><span class="text-link">{cp.name}</span>
              <span class="block text-[13px] text-hint">{cp.says === 'GIVES' ? 'Gives' : 'Wants'} {fmt(Number(cp.amount))} {cp.currency}{#if cp.chatTitle} · {cp.chatTitle}{/if}</span></div>
            <button class="btn btn-sm btn-outline btn-primary" onclick={() => onDone(cp.mineToken, cp.shortId)}>Done with</button>
          </div>
        {/each}
        {#if !m.counterparties.length}<p class="text-[13px] text-hint">No counterparty yet. You'll get a message when one appears.</p>{/if}
      </div>
    </article>
  {/each}
  {#if !me.mine.length}<p class="text-sm text-hint px-1">Nothing waiting. Post a request and it's matched in every chat that allows it.</p>{/if}
</section>
