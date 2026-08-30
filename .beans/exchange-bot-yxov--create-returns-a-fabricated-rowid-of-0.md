---
# exchange-bot-yxov
title: create() returns a fabricated rowId of 0
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-5
created_at: 2026-08-30T14:47:05Z
updated_at: 2026-08-30T14:47:05Z
parent: exchange-bot-utgz
---

The generated key is not fetched, so the returned Request carries rowId = 0. Nothing sorts
by rowId today, but a caller mixing this object with rows from resting() gets one that
compares as the oldest. Either fetch the generated key or document the field as unset on
this path. Source: Task 5 review, Minor.
