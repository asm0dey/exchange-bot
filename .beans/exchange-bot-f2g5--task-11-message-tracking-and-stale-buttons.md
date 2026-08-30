---
# exchange-bot-f2g5
title: 'Task 11: Message tracking and stale buttons'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T17:38:32Z
parent: exchange-bot-utgz
---

MessageLogRepository, V2 migration, ButtonService.
Commit 4f1275d, no fix round needed. Review confirmed the composite join closes cross-chat
leakage and the drift test was left unweakened. Corrected the plan's stripFor, which would
have replaced whole message bodies rather than just clearing their keyboards.
