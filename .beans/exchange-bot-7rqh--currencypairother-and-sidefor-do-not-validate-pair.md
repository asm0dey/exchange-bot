---
# exchange-bot-7rqh
title: CurrencyPair.other and sideFor do not validate pair membership
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-3
created_at: 2026-08-30T14:45:05Z
updated_at: 2026-08-30T14:45:05Z
parent: exchange-bot-utgz
---

An out-of-pair currency falls through silently: other() returns base, sideFor() returns
BID. Callers are expected to gate this with parseCurrency + CurrencyPair.contains, and
RequestService does, but the functions themselves are unguarded.
Source: Task 3 review, Minor.
