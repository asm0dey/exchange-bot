---
# exchange-bot-vxx3
title: Keyset parse failures do not say which keyset was malformed
status: todo
type: bug
priority: normal
tags:
    - deferred-minor
    - task-2
created_at: 2026-08-30T14:45:04Z
updated_at: 2026-08-30T14:45:04Z
parent: exchange-bot-utgz
---

Both keysets go through the same parse(), so a startup failure never names DATA_KEYSET vs
INDEX_KEYSET, and the underlying Tink/Gson exception propagates verbatim out of the
constructor. Gson's messages carry position rather than content so the leak risk is low,
but this is the one layer where 'never logged' is absolute. Wrap each call in a
GeneralSecurityException naming the keyset, without chaining the cause.
Source: Task 2 review, Minor 5.
