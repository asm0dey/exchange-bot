---
# exchange-bot-s3ji
title: No test would notice a transaction boundary widening or narrowing
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

Every RequestRepositoryTest case is single-threaded against a private in-memory database
with no failure injection, so splitting deleteFor's read and delete into two transactions,
or moving allocationLock inside transaction(db), would leave all 59 green. Those rules
survive by code reading alone. A concurrency test for create's short-id allocation is the
gap worth closing. Source: Task 5b review, Important (explicitly not a defect in the port).
