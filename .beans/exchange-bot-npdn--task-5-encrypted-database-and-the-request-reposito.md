---
# exchange-bot-npdn
title: 'Task 5: Encrypted database and the request repository'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:03Z
updated_at: 2026-08-30T15:46:55Z
parent: exchange-bot-utgz
---

Flyway V1 schema, encrypted DataSource, RequestRepository.
Commit 1e8d92f, 56/56 passing. DONE_WITH_CONCERNS: H2 rejects BLOB under MODE=PostgreSQL,
so db-scheduler's task_data is BYTEA (accepted, R13). Task review in flight.
