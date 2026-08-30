---
# exchange-bot-nj1w
title: No assertion that supplied keysets use the mandated algorithms
status: completed
type: bug
priority: normal
tags:
    - deferred-minor
    - task-2
created_at: 2026-08-30T14:45:04Z
updated_at: 2026-08-30T22:40:56Z
parent: exchange-bot-utgz
---

Keys arrive from the environment, so an operator supplying an AES-128-GCM keyset gets it
accepted silently while the spec mandates AES-256-GCM. KeysetGen produces the right thing
but is not the only source. Checking the parsed parameters at construction is cheap defence
in depth. Source: Task 2 review, Minor 6.

CLOSED: implemented by commit cc9ae6a ("fix: Crypto rejects keysets that satisfy Tink but not
the mandated algorithm"). `Crypto.init` now calls `requireAes256Gcm`/`requireHmacSha256` against
every ENABLED key in `DATA_KEYSET`/`INDEX_KEYSET` at construction and fails loudly with a
`GeneralSecurityException` naming the keyset if a weaker algorithm is supplied. Verified by
reading `src/main/kotlin/fxbot/Crypto.kt` and the commit's added `CryptoTest.kt` coverage.
