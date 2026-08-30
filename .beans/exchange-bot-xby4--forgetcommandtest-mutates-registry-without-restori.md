---
# exchange-bot-xby4
title: ForgetCommandTest mutates Registry without restoring it
status: todo
type: bug
priority: normal
tags:
    - deferred-minor
    - task-12
created_at: 2026-08-30T19:25:51Z
updated_at: 2026-08-30T19:25:51Z
parent: exchange-bot-utgz
---

Registry is process-global mutable state and ForgetCommandTest assigns three of its
fields (requests, messages, forget) without restoring the previous values. Benign today - it
is the only test that touches Registry, and kotest runs specs sequentially by default - but
it leaks into any later handler test and becomes a Heisenbug the day specs run in parallel.
Fix is a save-and-restore around the test, or a helper that installs a Registry for the
duration of one spec. Not a DI problem: see ruling R52 in the SDD ledger for why Kodein was
considered and declined.
