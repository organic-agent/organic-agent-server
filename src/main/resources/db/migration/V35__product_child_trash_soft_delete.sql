-- WES-259/260: product-side child removal now uses the same seven-day soft-delete contract as
-- admin operations. A removed retouch item may be added again while the old row remains restorable,
-- so uniqueness applies only to the currently active item.
ALTER TABLE retouch_photos
    DROP CONSTRAINT uk_retouch_photos_round_photo;

CREATE UNIQUE INDEX uk_retouch_photos_active_round_photo
    ON retouch_photos (round_id, photo_id)
    WHERE deleted_at IS NULL;
