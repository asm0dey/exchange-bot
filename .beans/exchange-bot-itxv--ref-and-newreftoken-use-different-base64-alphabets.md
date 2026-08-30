---
# exchange-bot-itxv
title: ref() and newRefToken() use different Base64 alphabets
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-2
created_at: 2026-08-30T14:45:04Z
updated_at: 2026-08-30T14:45:04Z
parent: exchange-bot-utgz
---

newRefToken() uses URL-safe unpadded; ref() uses standard, which emits + / and =. Harmless
in a TEXT column, but it makes ref() values hostile to any URL or t.me deep-link use and
invites a reader to assume both are URL-safe. Source: Task 2 review, Minor 7.
