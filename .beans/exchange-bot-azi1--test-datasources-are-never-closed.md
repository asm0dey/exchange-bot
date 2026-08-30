---
# exchange-bot-azi1
title: Test datasources are never closed
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

DB_CLOSE_DELAY=-1 with no ds.close() leaves an in-memory database and a Hikari pool alive
per test for the whole JVM. Harmless at this size; an afterSpec close is cheap.
Source: Task 5 review, Minor.
