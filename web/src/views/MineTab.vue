<script setup lang="ts">
import type { MeView } from '../api'
import { approx, fmt } from '../money'
defineProps<{ me: MeView }>()
defineEmits<{ confirm: [string, string]; refuse: [string, string]; cancel: [string]; done: [string, string] }>()
</script>
<template>
  <section class="flex flex-col gap-3.5">
    <div v-for="p in me.pending" :key="p.declarerToken + p.mineToken" class="bg-[color-mix(in_srgb,var(--color-give-soft)_70%,transparent)] rounded-box p-3.5 flex flex-col gap-2.5">
      <p class="text-sm"><b>{{ p.name }}</b> says you two swapped <b class="amount">{{ fmt(Number(p.amount)) }} {{ p.currency }} ⇄ {{ p.other }}</b>. Did it happen?</p>
      <div class="flex gap-2">
        <button class="btn btn-primary btn-sm flex-1" @click="$emit('confirm', p.declarerToken, p.mineToken)">Yes, we swapped</button>
        <button class="btn btn-sm flex-1 bg-base-100" @click="$emit('refuse', p.declarerToken, p.mineToken)">No</button>
      </div>
    </div>

    <div class="flex justify-between text-xs uppercase tracking-wider text-hint px-1"><span>Your requests</span><span>{{ me.mine.length }} of {{ me.limit }}</span></div>
    <article v-for="m in me.mine" :key="m.token" class="bg-base-100 rounded-box p-3.5 flex flex-col gap-2.5">
      <div class="flex justify-between items-baseline gap-2">
        <div><span class="amount font-semibold">{{ m.says === 'GIVES' ? 'Gives' : 'Wants' }} {{ fmt(Number(m.amount)) }} {{ m.currency }}</span>
          <span v-if="m.approxOther" class="text-[13px] text-hint"> for ≈ {{ approx(Number(m.approxOther)) }} {{ m.other }}</span></div>
        <button class="text-[13px] text-hint" @click="$emit('cancel', m.token)">Cancel</button>
      </div>
      <div class="flex flex-wrap gap-1.5">
        <span v-for="c in m.chats" :key="c.id" class="text-xs bg-base-200 text-hint px-2 py-0.5 rounded">{{ c.title ?? 'A group' }}</span>
        <span class="text-xs bg-base-200 text-hint px-2 py-0.5 rounded">No chat · bot-wide</span>
      </div>
      <div class="border-t border-base-300 pt-2 flex flex-col gap-2">
        <div v-for="cp in m.counterparties" :key="cp.mineToken + cp.shortId" class="flex justify-between items-center gap-2 text-sm">
          <div><span class="text-link">{{ cp.name }}</span>
            <span class="block text-[13px] text-hint">{{ cp.says === 'GIVES' ? 'Gives' : 'Wants' }} {{ fmt(Number(cp.amount)) }} {{ cp.currency }}<template v-if="cp.chatTitle"> · {{ cp.chatTitle }}</template></span></div>
          <button class="btn btn-sm btn-outline btn-primary" @click="$emit('done', cp.mineToken, cp.shortId)">Done with</button>
        </div>
        <p v-if="!m.counterparties.length" class="text-[13px] text-hint">No counterparty yet. You'll get a message when one appears.</p>
      </div>
    </article>
    <p v-if="!me.mine.length" class="text-sm text-hint px-1">Nothing waiting. Post a request and it's matched in every chat that allows it.</p>
  </section>
</template>
