-- ----------------------------------------------------------------------------
-- V15: align messages 表与 Message entity
-- ----------------------------------------------------------------------------
-- entity 含 media_url + ref_moment_id 但 V1 messages 未创建这两列。
-- ----------------------------------------------------------------------------

ALTER TABLE messages
    ADD COLUMN media_url     TEXT NULL,
    ADD COLUMN ref_moment_id BIGINT NULL;

CREATE INDEX idx_messages_ref_moment_id ON messages(ref_moment_id);
