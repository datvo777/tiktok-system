ALTER TABLE social.comment
    ADD COLUMN parent_comment_id UUID NULL REFERENCES social.comment (comment_id);

CREATE INDEX comment_parent_idx ON social.comment (parent_comment_id, created_at);
