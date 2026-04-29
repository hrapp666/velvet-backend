-- ----------------------------------------------------------------------------
-- V14: align conversations 表与 Conversation entity
-- ----------------------------------------------------------------------------
-- entity 含 last_message_id (BIGINT) + updated_at (TIMESTAMP) 但 V1 未创建。
-- last_message TEXT 已被 last_message_id (FK -> messages) 取代，但保留旧列避免破坏可能的旧数据。
-- ----------------------------------------------------------------------------

ALTER TABLE conversations
    ADD COLUMN last_message_id BIGINT NULL AFTER last_message,
    ADD COLUMN updated_at      TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

CREATE INDEX idx_conversations_last_message_id ON conversations(last_message_id);
