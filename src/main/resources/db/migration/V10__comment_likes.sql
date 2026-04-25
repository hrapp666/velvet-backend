-- Comment likes table for toggle support
CREATE TABLE IF NOT EXISTS comment_likes (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id),
    comment_id  BIGINT NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, comment_id)
);

CREATE INDEX idx_comment_likes_comment ON comment_likes (comment_id);
CREATE INDEX idx_comment_likes_user    ON comment_likes (user_id);
