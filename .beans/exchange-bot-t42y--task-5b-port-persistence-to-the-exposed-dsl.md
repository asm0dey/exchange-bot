---
# exchange-bot-t42y
title: 'Task 5b: Port persistence to the Exposed DSL'
status: todo
type: task
priority: high
created_at: 2026-08-30T15:11:15Z
updated_at: 2026-08-30T15:11:15Z
parent: exchange-bot-utgz
---

exposed-core + exposed-jdbc only, as a query layer. Removes hand-built SQL strings and
positional parameter binding. Tink and Flyway unchanged; exposed-crypt/money/migration/dao
deliberately not adopted (see ruling R22). Includes a schema-drift test, because Flyway owns
the schema and Tables.kt describes it a second time (R23). No behaviour change: all 59
existing tests must pass untouched.
