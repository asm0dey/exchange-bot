---
# exchange-bot-d4ld
title: ktor-serialization-json is an unused dependency
status: todo
type: task
priority: low
tags:
    - deferred-minor
    - task-7
created_at: 2026-08-30T16:19:40Z
updated_at: 2026-08-30T16:19:40Z
parent: exchange-bot-utgz
---

Same class as ktor-client-content-negotiation, which was removed in Task 7's fix round.
Nothing installs it; RateClient parses JSON directly with kotlinx-serialization. Confirm it
is still unused once Main.kt exists (Task 9), then drop it.
Source: Task 7 re-review, out-of-scope observation.
