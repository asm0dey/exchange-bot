---
# exchange-bot-jhsu
title: 'Task 6: Chat settings'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:04Z
updated_at: 2026-08-30T15:59:25Z
parent: exchange-bot-utgz
---

ChatSettingsRepository on the Exposed DSL: pair, size tolerance, time in force per chat.
Commits f70bb8d, 9228625 (fix round 1). Review confirmed the migration test proves a genuine
reseal rather than a ciphertext copy. Fix round added chatId to the sealed payload (same
class of defect as R7 - a MAC rotation could not otherwise re-derive chat_ref, since
chat_settings' AAD IS that ref) and a permanent test for the upsert update path.
