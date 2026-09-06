ALTER TABLE galleries
    ADD COLUMN photo_organization_required boolean NOT NULL DEFAULT false,
    ADD COLUMN folders_saved_at timestamptz,
    ADD COLUMN retouch_confirmed_at timestamptz,
    ADD COLUMN archived_until timestamptz,
    ADD COLUMN plan_expires_at timestamptz,
    ADD COLUMN plan_max_photo_count integer CHECK (plan_max_photo_count > 0);

ALTER TABLE collab_sessions
    ADD COLUMN include_all_albums boolean NOT NULL DEFAULT false,
    ADD COLUMN cover_title varchar(100),
    ADD COLUMN cover_author varchar(100);

CREATE TABLE test_checkouts (
    id varchar(36) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id varchar(100) NOT NULL,
    amount bigint NOT NULL CHECK (amount >= 0),
    currency varchar(3) NOT NULL,
    max_photo_count integer NOT NULL CHECK (max_photo_count > 0),
    expires_at timestamptz NOT NULL,
    gallery_id bigint REFERENCES galleries(id) ON DELETE SET NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_test_checkouts_user_id ON test_checkouts(user_id);
CREATE INDEX idx_galleries_plan_expires_at ON galleries(plan_expires_at) WHERE plan_expires_at IS NOT NULL;
