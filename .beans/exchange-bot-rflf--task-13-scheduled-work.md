---
# exchange-bot-rflf
title: 'Task 13: Scheduled work'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T19:14:32Z
parent: exchange-bot-utgz
---

Housekeeping + startScheduler: daily expiry sweep, 90-day message prune, daily rate
refresh. Commits 48c9ce5, c438e7d (fix round 1). Review found that a merely SLOW rate feed
could stop the bot from starting - ktor implements timeouts as job cancellation, RateClient
rethrows cancellation by design, and the startup refresh was unguarded and ran before the bot
was constructed. Two correct decisions producing a failure neither predicted.
