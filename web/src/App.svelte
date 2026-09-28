<script lang="ts">
  import { initTelegram, startChatId, tg } from './tg'
  import Blocked from './views/Blocked.svelte'
  import GroupView from './views/GroupView.svelte'
  import PrivateView from './views/PrivateView.svelte'

  initTelegram()
  const chatId = startChatId()
  let blocked = $state<string | null>(tg ? null : 'Open this from Telegram.')
  const onAuthLost = () => { blocked = 'Reopen from Telegram.' }
</script>

{#if blocked}
  <Blocked message={blocked} />
{:else if chatId !== null}
  <GroupView {chatId} {onAuthLost} />
{:else}
  <PrivateView {onAuthLost} />
{/if}
