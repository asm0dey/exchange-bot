---
# exchange-bot-arq0
title: Redaction replaces the whole message body, not one person's line
status: todo
type: task
priority: normal
tags:
    - deferred-minor
    - task-12
created_at: 2026-08-30T17:47:56Z
updated_at: 2026-08-30T17:47:56Z
parent: exchange-bot-utgz
---

Real per-line redaction would need: a query enumerating a message's full refToken set
(only a boolean and a message-level view exist today), re-fetching each surviving
counterparty's current request state - which may have closed since, raising unspecified UX
questions - and a 'render for subject minus one counterparty' template that does not exist.
Materially larger than Task 12. ADR 0005 and the spec have been amended to describe what
actually ships rather than leave them contradicting the code (ruling R47).
Source: Task 12 implementer concern, confirmed by review.
