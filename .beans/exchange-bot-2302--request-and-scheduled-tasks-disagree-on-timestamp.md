---
# exchange-bot-2302
title: request and scheduled_tasks disagree on timestamp zone handling
status: todo
type: bug
priority: normal
tags:
    - deferred-minor
    - task-5
created_at: 2026-08-30T14:47:05Z
updated_at: 2026-08-30T14:47:05Z
parent: exchange-bot-utgz
---

request uses TIMESTAMP while db-scheduler's table uses TIMESTAMP WITH TIME ZONE, and
Timestamp.from/toInstant round-trip through the JVM default zone. On a non-UTC host an
instant landing in a repeated DST hour reads back an hour off, shifting an expiry. Either
pin the JVM to UTC explicitly or use TIMESTAMP WITH TIME ZONE for request too.
Source: Task 5 review, Minor.
