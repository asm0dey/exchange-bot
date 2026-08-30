---
# exchange-bot-jdgd
title: 'Task 15: Supergroup migration'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:05Z
updated_at: 2026-08-30T19:57:02Z
parent: exchange-bot-utgz
---

ChatMigrationService + the migrate_to_chat_id handler.
Commits 6e9da8d, eb7715e (fix round 1). Fix round made the migration atomic: the implementer
had ruled that out of scope believing it needed three repository constructors changed, but all
three already share one Database and Exposed joins nested transactions by default, so it cost
one parameter. The implementer also found a systemic crash-loop risk in the library's
deserialization that affects every handler - filed, not fixed.
