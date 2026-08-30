---
# exchange-bot-qq40
title: stripFor clears a whole keyboard when one of its tokens closes
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-11
created_at: 2026-08-30T17:38:32Z
updated_at: 2026-08-30T17:38:32Z
parent: exchange-bot-utgz
---

A suggestion message can list several counterparties. When one of them closes, stripFor
clears the entire keyboard, so buttons for the still-open ones disappear too and the surviving
text lists people you can no longer press Done with. Granularity inherited from the
stripFor(tokens, chatId, bot) interface specified in Task 10, not a Task 11 regression.
Source: Task 11 review, design note.
