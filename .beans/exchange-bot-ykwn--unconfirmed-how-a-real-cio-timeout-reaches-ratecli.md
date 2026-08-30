---
# exchange-bot-ykwn
title: 'Unconfirmed: how a real CIO timeout reaches RateClient'
status: todo
type: task
priority: low
tags:
    - deferred-minor
    - task-13
created_at: 2026-08-30T19:14:32Z
updated_at: 2026-08-30T19:14:32Z
parent: exchange-bot-utgz
---

RateClient degrades a CancellationException whose cause is HttpRequestTimeoutException
to null. A synthetic reproduction showed kotlinx.coroutines unwraps a cancellation carrying a
non-null cause and surfaces the bare cause, so a real CIO timeout may arrive as a bare
HttpRequestTimeoutException - already handled by the generic catch - making that branch
inert. Harmless either way, and the startup fix that actually resolved the failure is
independent of it. Confirming needs a live CIO call against a deliberately slow listener.
Source: Task 13 re-review, rulings R50/R51.
