---
# exchange-bot-dbw1
title: /help lists commands that do not exist yet
status: todo
type: bug
priority: normal
tags:
    - deferred-minor
    - task-9
created_at: 2026-08-30T16:54:53Z
updated_at: 2026-08-30T16:54:53Z
parent: exchange-bot-utgz
---

Same class as the setMyCommands trim (R39): /help's body still lists /cancel, /done,
/reopen and /forget, whose handlers arrive in Tasks 10 and 12. Each of those tasks should add
its own line to /help as its handler lands, so the help text never advertises a dead command.
Source: Task 9 re-review, out-of-scope observation.
