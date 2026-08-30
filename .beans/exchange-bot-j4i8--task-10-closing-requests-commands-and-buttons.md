---
# exchange-bot-j4i8
title: 'Task 10: Closing requests - commands and buttons'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T17:23:28Z
parent: exchange-bot-utgz
---

LifecycleService, /cancel /done /reopen, and the callback handlers.
Commits 9630d64, b129b71 (fix round 1). Review found a Critical: holding one token
authorised closing both requests, and both tokens ride in group-visible callback_data, so
any member could drop a stranger from the waitlist silently. Now requires the presser to own
one AND the pair to be plausible (same chat, opposite sides, different people).
