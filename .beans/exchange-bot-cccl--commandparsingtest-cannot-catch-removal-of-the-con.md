---
# exchange-bot-cccl
title: CommandParsingTest cannot catch removal of the config line from Main.kt
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-9
created_at: 2026-08-30T16:54:53Z
updated_at: 2026-08-30T16:54:53Z
parent: exchange-bot-utgz
---

The test builds its own isolated TelegramBot, so it verifies the framework's
restrictSpacesInCommands semantics but would not fail if someone deleted the line from
Main.kt's builder. main() is an unextracted entry point, so closing this means extracting the
builder configuration to something testable. Structural limit, not sloppiness.
Source: Task 9 re-review, caveat.
