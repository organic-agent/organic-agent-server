ALTER TABLE retouch_photos ADD COLUMN request_points jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE retouch_photos ADD CONSTRAINT ck_retouch_photo_points_array CHECK (jsonb_typeof(request_points) = 'array');
