<script lang="ts">
  let { pct, error, onSave }: { pct: number; error: string | null; onSave: (p: number) => void } = $props()
  let value = $state(0)
  $effect.pre(() => { value = pct })
</script>

<section class="bg-base-100 rounded-box p-4 flex flex-col gap-3">
  <label for="tol" class="font-semibold">Size tolerance: <span class="amount">{value}%</span></label>
  <input id="tol" bind:value type="range" min="1" max="100" class="range range-primary range-sm" />
  <p class="text-[13px] text-hint">A counterparty matches when what they'd leave you is within {value}% of your own amount. It applies to requests you post here, never to a group's own setting.</p>
  <button class="btn btn-primary" disabled={value === pct} onclick={() => onSave(value)}>Save</button>
  {#if error}<p class="text-sm text-error" role="alert">{error}</p>{/if}
</section>
