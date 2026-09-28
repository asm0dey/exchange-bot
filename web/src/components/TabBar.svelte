<script lang="ts">
  import { ArrowUpRight, Search, SlidersHorizontal } from '@lucide/svelte'
  let { tab = $bindable() }: { tab: 'mine' | 'browse' | 'tolerance' } = $props()
  const tabs = [
    { key: 'mine', label: 'Mine', icon: ArrowUpRight },
    { key: 'browse', label: 'Browse', icon: Search },
    { key: 'tolerance', label: 'Tolerance', icon: SlidersHorizontal },
  ] as const
</script>

<div class="fixed inset-x-0 bottom-0 grid grid-cols-3 bg-base-100 border-t border-base-300 text-[11px] text-center text-hint pb-[env(safe-area-inset-bottom)]" role="tablist">
  {#each tabs as t (t.key)}
    <button role="tab" aria-selected={tab === t.key}
            class={['flex flex-col items-center gap-0.5 pt-2 pb-3', tab === t.key && 'text-primary font-semibold']} onclick={() => (tab = t.key)}>
      <t.icon size={22} />{t.label}
    </button>
  {/each}
</div>
