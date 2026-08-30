---
# exchange-bot-e3ds
title: expireDue's use of its own now parameter is untested
status: todo
type: bug
priority: normal
tags:
    - deferred-minor
    - task-5b
created_at: 2026-08-30T15:40:42Z
updated_at: 2026-08-30T15:40:42Z
parent: exchange-bot-utgz
---

If expireDue stamped clock.instant() instead of its now parameter, the fixed test clock
would give both requests the same closed_at and the rowId tiebreaker would still return the
expected row — the test passes either way. The rule is correct in code but uncovered.
Source: Task 5b review, Important.
