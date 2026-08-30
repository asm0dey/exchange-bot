---
# exchange-bot-6yqv
title: ButtonService has no test of its own
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

No coverage of the edit call shape (right chat_id/message_id) or of one failed edit not
aborting the rest of the fan-out. Matches the repo convention of not wire-testing Telegram
sends, but it is the one path in Task 11 with no automated coverage.
Source: Task 11 review, Minor.
