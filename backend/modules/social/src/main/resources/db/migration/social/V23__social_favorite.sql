-- Favorite collections (saved videos), owned by the Social module alongside
-- likes, comments and follows.
--
-- Deliberately separate from social.video_like: a like is a public signal that
-- feeds ranking, while a save is a private organisational act. Collapsing them
-- would mean either publishing what a viewer has filed away or letting an
-- un-save silently retract a like.
CREATE TABLE social.favorite_collection (
    collection_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    name VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- One name per owner, case-insensitively: "Watch later" and "watch later" in the
-- same picker are indistinguishable to the person choosing between them.
CREATE UNIQUE INDEX favorite_collection_owner_name_idx
    ON social.favorite_collection (account_id, lower(name));

CREATE INDEX favorite_collection_owner_idx ON social.favorite_collection (account_id, created_at);

CREATE TABLE social.favorite_item (
    collection_id UUID NOT NULL REFERENCES social.favorite_collection (collection_id) ON DELETE CASCADE,
    video_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, video_id)
);

-- Answers "which of my collections already hold this video?" -- the picker's
-- query, run every time the viewer opens the save sheet on a video.
CREATE INDEX favorite_item_video_idx ON social.favorite_item (video_id);
