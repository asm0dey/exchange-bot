---
# exchange-bot-e6fx
title: Matcher recomputes notionals inside the candidate loop
status: todo
type: task
priority: deferred
tags:
    - deferred-minor
    - task-4
created_at: 2026-08-30T14:45:05Z
updated_at: 2026-08-30T14:45:05Z
parent: exchange-bot-utgz
---

comparableSizes recomputes the subject's notional for every candidate even though the
subject is fixed, and each surviving candidate's notional is computed twice. DECLINED for
now (ruling R12 in the SDD ledger): at one chat's scale this cost is nil, and optimising
without a measurement is how simple code stops being simple. Recorded so the final review
can disagree. Source: Task 4 review, Minors.
