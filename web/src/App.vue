<script setup lang="ts">
import { ref } from 'vue'
import { initTelegram, startChatId, tg } from './tg'
import Blocked from './views/Blocked.vue'
import GroupView from './views/GroupView.vue'
import PrivateView from './views/PrivateView.vue'

initTelegram()
const chatId = startChatId()
const blocked = ref<string | null>(tg ? null : 'Open this from Telegram.')
const onAuthLost = () => { blocked.value = 'Reopen from Telegram.' }
</script>

<template>
  <Blocked v-if="blocked" :message="blocked" />
  <GroupView v-else-if="chatId !== null" :chat-id="chatId" @auth-lost="onAuthLost" />
  <PrivateView v-else @auth-lost="onAuthLost" />
</template>
