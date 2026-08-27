-- WES-256/257/259/260: durable executor claim, restorable collaboration likes,
-- and studio-owned reusable album-template consistency.

ALTER TABLE admin_notification_outbox
    ADD COLUMN last_run_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE admin_notification_outbox
    DROP CONSTRAINT ck_admin_notification_status;

ALTER TABLE admin_notification_outbox
    ADD CONSTRAINT ck_admin_notification_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELED'));

-- Likes follow the same seven-day child-trash policy as comments. The partial unique index lets a
-- deleted row coexist only while it is recoverable; restore still fails closed on a new active like.
ALTER TABLE collab_photo_likes
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE collab_photo_likes
    DROP CONSTRAINT uk_collab_photo_likes_photo_guest;

CREATE UNIQUE INDEX uk_collab_photo_likes_active_photo_guest
    ON collab_photo_likes (collab_photo_id, collab_guest_id)
    WHERE deleted_at IS NULL;

ALTER TABLE admin_child_trash_records
    DROP CONSTRAINT ck_admin_child_trash_resource_parent;

ALTER TABLE admin_child_trash_records
    ADD CONSTRAINT ck_admin_child_trash_resource_parent
        CHECK (
            (resource_type = 'COLLAB_COMMENT' AND parent_type = 'COLLABORATION')
            OR (resource_type = 'COLLAB_LIKE' AND parent_type = 'COLLABORATION')
            OR (resource_type = 'ALBUM_TEMPLATE' AND parent_type = 'ALBUM')
            OR (resource_type = 'RETOUCH_ITEM' AND parent_type = 'RETOUCH_REQUEST')
        );

-- Templates belong to one studio and may be reused by albums across that studio's galleries.
-- Existing ownership is inferred only when every current reference agrees; ambiguous/orphan data
-- fails the migration instead of receiving an arbitrary studio.
ALTER TABLE admin_album_templates ADD COLUMN studio_id BIGINT;

DO $$
BEGIN
    IF EXISTS (
        SELECT g.template_id
        FROM photo_folder_groups g
        JOIN galleries ga ON ga.id = g.gallery_id
        WHERE g.template_id IS NOT NULL
        GROUP BY g.template_id
        HAVING COUNT(DISTINCT ga.studio_id) > 1
    ) THEN
        RAISE EXCEPTION 'album template is referenced across studios';
    END IF;
END
$$;

UPDATE admin_album_templates t
SET studio_id = owner.studio_id
FROM (
    SELECT g.template_id, MIN(ga.studio_id) AS studio_id
    FROM photo_folder_groups g
    JOIN galleries ga ON ga.id = g.gallery_id
    WHERE g.template_id IS NOT NULL
    GROUP BY g.template_id
) owner
WHERE owner.template_id = t.id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM admin_album_templates WHERE studio_id IS NULL) THEN
        RAISE EXCEPTION 'album template owner cannot be inferred';
    END IF;
END
$$;

ALTER TABLE admin_album_templates
    ALTER COLUMN studio_id SET NOT NULL,
    ADD CONSTRAINT fk_admin_album_templates_studio
        FOREIGN KEY (studio_id) REFERENCES studios (id),
    DROP CONSTRAINT uk_admin_album_template_name;

CREATE UNIQUE INDEX uk_admin_album_templates_studio_name
    ON admin_album_templates (studio_id, name);
CREATE INDEX idx_admin_album_templates_studio
    ON admin_album_templates (studio_id, id);
