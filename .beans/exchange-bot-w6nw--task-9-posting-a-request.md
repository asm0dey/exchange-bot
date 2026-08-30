---
# exchange-bot-w6nw
title: 'Task 9: Posting a request'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T16:54:53Z
parent: exchange-bot-utgz
---

RequestService + Registry + Commands + Main. Commits b56f4ed, ede2362 (fix round 1).
Review found a Critical no green suite could catch: vendeli does not break commands on a
space by default, so '/sell 1000 EUR' was taken as the whole command name and dropped
silently - the bot answered only when a client appended the bot username. Fixed with
restrictSpacesInCommands and pinned by a test driving the real framework pipeline.
