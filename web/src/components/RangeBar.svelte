<script lang="ts">
  let { min, max, value }: { min: number; max: number | null; value: number | null } = $props()
  /** Scale: from 0 to 1.6× the range's top (or 2× its floor when open-ended). */
  const top = $derived((max ?? min * 2) * 1.6)
  const pct = (n: number) => `${Math.max(0, Math.min(100, (n / top) * 100))}%`
</script>

<div class="relative h-[18px]" aria-hidden="true">
  <i class="absolute inset-x-0 top-2 h-0.5 rounded bg-base-300"></i>
  <i class="absolute top-[5px] h-2 rounded bg-want-soft ring-[1.5px] ring-want ring-inset"
     style:left={pct(min)} style:width={max === null ? `calc(100% - ${pct(min)})` : `calc(${pct(max)} - ${pct(min)})`}></i>
  {#if value !== null}
    <i class="absolute top-px h-4 w-[3px] -ml-px rounded bg-base-content" style:left={pct(value)}></i>
  {/if}
</div>
