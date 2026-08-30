---
# exchange-bot-3e7e
title: Config leak test checks only one secret against the exception message
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-1
created_at: 2026-08-30T14:45:04Z
updated_at: 2026-08-30T14:45:04Z
parent: exchange-bot-utgz
---

ConfigTest's 'never puts a secret value in the message' asserts only that DB_USER_PW's
value is absent from the failure message, not all five. The separate toString test added in
Task 2's fix round does check all five, so this is the narrower of the two paths.
Source: Task 1 review, Minor.
