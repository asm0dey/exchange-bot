---
# exchange-bot-cwup
title: TasksTest starts a real Scheduler it never stops
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-13
created_at: 2026-08-30T19:14:32Z
updated_at: 2026-08-30T19:14:32Z
parent: exchange-bot-utgz
---

The startScheduler test starts a real 2-thread Scheduler and never shuts it down. No
observed flakiness at 139/139, but it leaks threads for the JVM's life.
Source: Task 13 re-review, out-of-scope observation.
