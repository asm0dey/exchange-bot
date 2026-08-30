---
# exchange-bot-t42y
title: 'Task 5b: Port persistence to the Exposed DSL'
status: completed
type: task
priority: high
created_at: 2026-08-30T15:11:15Z
updated_at: 2026-08-30T15:47:03Z
parent: exchange-bot-utgz
---

Ported RequestRepository to the Exposed DSL: no SQL strings, no positional binding.
Commits a9f9436, a24d957 (fix round 1). Review verified every rule from Task 5's two fix
rounds survived, line by line. Drift test caught an invented sent_message.payload column in
the plan's own sketch. Fix round: memoised Database per DataSource (Exposed's static
registry never unregisters), disabled the retry policy the port silently inherited
(defaultMaxAttempts 3 -> 1), documented the deprecated drift API and the timezone
dependency's real location.
