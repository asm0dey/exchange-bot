---
# exchange-bot-imbi
title: 'Task 14: Admin settings'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:05Z
updated_at: 2026-08-30T19:37:19Z
parent: exchange-bot-utgz
---

AdminService + /pair /tolerance /tif, admin-gated.
Commits d98d140, 52fd67e (fix round 1). Reviewer disassembled the framework jar to confirm
the permission check genuinely fails closed on API failure, transport error and malformed
response, and is an allowlist. Fix round added the tests that path never had - its
correctness had rested entirely on two people reading bytecode.
