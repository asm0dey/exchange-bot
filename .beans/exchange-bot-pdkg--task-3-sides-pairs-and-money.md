---
# exchange-bot-pdkg
title: 'Task 3: Sides, pairs, and money'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:03Z
updated_at: 2026-08-30T14:41:25Z
parent: exchange-bot-utgz
---

Side/Verb/CurrencyPair, sideFor, parseCurrency, money parse+format.
Commits ab9478f, 59ba4ef (fix round 1). Review found shared DecimalFormat instances are not
thread-safe under concurrent handlers; fixed with per-call construction.
