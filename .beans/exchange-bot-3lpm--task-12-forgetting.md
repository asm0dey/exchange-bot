---
# exchange-bot-3lpm
title: 'Task 12: Forgetting'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T18:01:49Z
parent: exchange-bot-utgz
---

ForgetService, /forget and /forget all, message cleanup.
Commits c456943, 0db0732 (fix round 1). Implementer caught that ADR 0005 contradicted the
code on per-line redaction - the ADR was wrong and has been amended. The fix round then found
that runCatching around .send() catches nothing, because throwExOnActionsFailure defaults
false; now uses sendReturning().isSuccess(). Added ForgetCommandTest, the first handler-level
branch test in the codebase.
