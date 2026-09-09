-- Plaintext like ref_token: random, carries no personal data, and a sealed payload
-- cannot be queried, so the sibling link has to be a column. Rows predating V3 keep
-- NULL and are interests of one showing.
ALTER TABLE request ADD COLUMN interest_token TEXT;
CREATE INDEX request_interest_idx ON request (interest_token);

-- One person's own size tolerance, used only for working with the bot. Sealed like chat_settings;
-- the AAD is the user_ref, so forgetting deletes it with the predicate the schema
-- already uses everywhere else.
CREATE TABLE person_settings (
    user_ref   TEXT PRIMARY KEY,
    payload    BYTEA     NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- One row per direction of a pairing. Two 'offered' rows mean both consented; one
-- 'declined' row suppresses the pairing for both. Rows die with their requests.
CREATE TABLE name_give_up (
    ref_token      TEXT      NOT NULL,
    peer_ref_token TEXT      NOT NULL,
    user_ref       TEXT      NOT NULL,
    stance         TEXT      NOT NULL,
    decided_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (ref_token, peer_ref_token)
);
CREATE INDEX name_give_up_user_idx ON name_give_up (user_ref);

-- What to announce, never how: the text is re-rendered from live state at flush time.
CREATE TABLE pending_announcement (
    chat_ref       TEXT      NOT NULL,
    interest_token TEXT      NOT NULL,
    user_ref       TEXT      NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (chat_ref, interest_token)
);
CREATE INDEX pending_announcement_user_idx ON pending_announcement (user_ref);
