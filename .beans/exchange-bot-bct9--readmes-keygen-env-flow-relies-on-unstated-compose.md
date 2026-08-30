---
# exchange-bot-bct9
title: README's keygen >> .env flow relies on unstated Compose behaviour
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-16
created_at: 2026-08-30T20:08:32Z
updated_at: 2026-08-30T20:08:32Z
parent: exchange-bot-utgz
---

cp .env.example .env followed by ./gradlew keygen -q >> .env leaves DATA_KEYSET and
INDEX_KEYSET declared twice - once empty, once real. It works only because env_file takes the
last occurrence, which a new deployer has no way to know from the README. Either note it or
use a sed replace instead of an append.
Source: Task 16 review, Minor.
