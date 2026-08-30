---
# exchange-bot-vsjf
title: A member can force-close an unrelated opposite-side request
status: todo
type: bug
priority: normal
tags:
    - accepted-residual
    - task-10
created_at: 2026-08-30T17:23:28Z
updated_at: 2026-08-30T17:23:28Z
parent: exchange-bot-utgz
---

Accepted, ruling R45. The done pairing check permits any same-chat, opposite-side,
different-owner pair, so a member can forge a payload closing a stranger's resting request
without ever having dealt with them. Closing it properly would require either peer
confirmation (the author chose unilateral done deliberately) or a record of which pairs were
suggested (ADR 0001 rejects storing suggestions). Mitigated by the closure being announced
publicly naming both people, and by /reopen. Revisit at the final whole-branch review.
Source: Task 10 re-review, out-of-scope observation.
