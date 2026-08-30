---
# exchange-bot-dxxf
title: 'Task 4: The matcher'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:03Z
updated_at: 2026-08-30T14:41:25Z
parent: exchange-bot-utgz
---

Request/RequestState, notional(), findCounterparties().
Commits f5d7ead, 3dcca2c (fix round 1). Review found the both-OPEN rule was only half
enforced and the rate division had no positivity guard; both fixed. 2 perf minors declined.
