---
# exchange-bot-4l4a
title: rewriteChatRef prepares its UPDATE inside the per-row loop
status: todo
type: task
priority: deferred
tags:
    - deferred-minor
    - task-5
created_at: 2026-08-30T14:47:05Z
updated_at: 2026-08-30T14:47:05Z
parent: exchange-bot-utgz
---

Hoist the statement above the loop and use addBatch/executeBatch. Bounded by one chat's
requests, so declined for now. Source: Task 5 review, Minor.
