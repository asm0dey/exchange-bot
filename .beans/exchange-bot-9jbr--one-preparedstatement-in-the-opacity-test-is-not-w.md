---
# exchange-bot-9jbr
title: One PreparedStatement in the opacity test is not wrapped in use
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

RequestRepositoryTest's raw SELECT payload check wraps the ResultSet but not the statement,
the one place breaking otherwise-consistent resource discipline.
Source: Task 5 review, Minor.
