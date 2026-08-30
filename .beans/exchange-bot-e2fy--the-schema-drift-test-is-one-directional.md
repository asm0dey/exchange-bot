---
# exchange-bot-e2fy
title: The schema drift test is one-directional
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-5b
created_at: 2026-08-30T15:40:42Z
updated_at: 2026-08-30T15:40:42Z
parent: exchange-bot-utgz
---

statementsRequiredToActualizeScheme emits statements to bring the database up to Tables.kt,
so it catches renames and columns invented in Kotlin (which is how the sent_message.payload
sketch bug was found) but NOT a column present in the migration and missing from Tables.kt,
nor reliably a type-only difference. Source: Task 5b review, Minor.
