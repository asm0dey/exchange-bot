---
# exchange-bot-dbw1
title: /help lists commands that do not exist yet
status: completed
type: bug
priority: normal
tags:
    - deferred-minor
    - task-9
created_at: 2026-08-30T16:54:53Z
updated_at: 2026-08-30T21:14:46Z
parent: exchange-bot-utgz
---

CLOSED AS STALE, not fixed. Verified: every command listed in /help (/sell /buy
/status /cancel /done /reopen /settings /pair /tolerance /tif /forget) has a live
@CommandHandler, and /help and /start are handlers too. The menu-and-help discipline held as
each task added its own entries. Confirmed by the whole-branch review and re-checked at
close.
