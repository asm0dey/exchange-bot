<script lang="ts">
  let { message }: { message: string } = $props()
</script>

<main class="min-h-dvh grid place-items-center p-6 text-center">
  <div class="flex flex-col gap-2 max-w-xs">
    <h1 class="text-lg font-semibold">Exchange</h1>
    <p class="text-hint">{message}</p>
  </div>
</main>
