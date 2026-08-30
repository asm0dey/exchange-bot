---
# exchange-bot-2jp2
title: 'Task 7: The reference rate'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T16:19:40Z
parent: exchange-bot-utgz
---

RateClient/RateRepository/RateService with the three-state degradation ladder.
Commits 3dbbc3a, 33c6c2c (fix round 1). Implementer caught that the plan's own sample parsed
rates as Map<String,Double> - the precision trap the constraints forbid - and proved it with
an 18-digit test. Fix round: rethrow CancellationException (runCatching was swallowing it,
so a cancelled scheduled refresh would keep running), pin the 7-day stale boundary exactly,
drop an unused dependency.
