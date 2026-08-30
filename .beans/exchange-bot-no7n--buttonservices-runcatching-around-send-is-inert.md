---
# exchange-bot-no7n
title: ButtonService's runCatching around .send() is inert
status: todo
type: bug
priority: normal
tags:
    - deferred-minor
    - task-11
created_at: 2026-08-30T18:01:49Z
updated_at: 2026-08-30T18:01:49Z
parent: exchange-bot-utgz
---

Verified against telegram-bot-jvm-9.6.0-sources: throwExOnActionsFailure defaults false
and makeSilentRequest only throws when it is set, so Action.send() never throws on a
Telegram-side failure and this runCatching catches nothing. Task 11's review credited it as
real partial-failure isolation; that was wrong.
Behaviour is nonetheless correct - the loop continues because nothing throws - and stripFor's
failures are covered by the on-press rejection path, so this is a misleading construct rather
than a live defect. Either drop the runCatching with a comment explaining that sends do not
throw, or switch to sendReturning().isSuccess() as /forget now does.
Source: Task 12 re-review, root-cause verification.
