-- Final product-policy contract after V5 additive backfill verification.

-- The dedicated album feature was intentionally removed. Existing workflow
-- rows that used its stage continue at the delivery stage.
ALTER TABLE public.galleries DROP CONSTRAINT IF EXISTS ck_galleries_stage;
UPDATE public.galleries SET stage = 'DELIVERY' WHERE stage = 'ALBUM';
ALTER TABLE public.galleries ADD CONSTRAINT ck_galleries_stage CHECK (
    stage IN ('UPLOAD', 'SELECTION_IN_PROGRESS', 'SELECTION_COMPLETED', 'RETOUCH', 'DELIVERY', 'ARCHIVED')
);

-- A selection exists from gallery creation onward, including trashed historical galleries.
INSERT INTO public.photo_selections
    (gallery_id, status, version, created_at, updated_at)
SELECT gallery.id, 'SELECTING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM public.galleries gallery
WHERE NOT EXISTS (SELECT 1 FROM public.photo_selections selection WHERE selection.gallery_id = gallery.id)
ON CONFLICT (gallery_id) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.detail_folders WHERE gallery_id IS NULL) THEN
        RAISE EXCEPTION 'detail_folders.gallery_id backfill incomplete';
    END IF;
    IF EXISTS (SELECT 1 FROM public.photo_category_assignments WHERE gallery_id IS NULL) THEN
        RAISE EXCEPTION 'photo_category_assignments.gallery_id backfill incomplete';
    END IF;
    IF EXISTS (SELECT 1 FROM public.categorization_job_photos WHERE gallery_id IS NULL OR status IS NULL) THEN
        RAISE EXCEPTION 'categorization_job_photos backfill incomplete';
    END IF;
    IF EXISTS (SELECT 1 FROM public.photo_selection_items WHERE gallery_id IS NULL OR sort_order IS NULL) THEN
        RAISE EXCEPTION 'photo_selection_items backfill incomplete';
    END IF;
    IF EXISTS (SELECT 1 FROM public.collab_photo_comments WHERE participant_id IS NULL) OR
       EXISTS (SELECT 1 FROM public.collab_photo_likes WHERE participant_id IS NULL) THEN
        RAISE EXCEPTION 'collaboration participant backfill incomplete';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.studios studio
        WHERE studio.deleted_at IS NULL AND NOT EXISTS (
            SELECT 1 FROM public.workspace_members member
            WHERE member.workspace_id = studio.workspace_id
              AND member.role = 'OWNER' AND member.deleted_at IS NULL
        )
    ) THEN
        RAISE EXCEPTION 'active studio without OWNER';
    END IF;
END $$;

ALTER TABLE public.detail_folders ALTER COLUMN gallery_id SET NOT NULL;
ALTER TABLE public.photo_category_assignments ALTER COLUMN gallery_id SET NOT NULL;
ALTER TABLE public.categorization_job_photos ALTER COLUMN gallery_id SET NOT NULL;
ALTER TABLE public.categorization_job_photos ALTER COLUMN status SET NOT NULL;
ALTER TABLE public.photo_selection_items ALTER COLUMN gallery_id SET NOT NULL;
ALTER TABLE public.photo_selection_items ALTER COLUMN sort_order SET NOT NULL;

ALTER TABLE public.detail_folders
    ADD CONSTRAINT fk_detail_folders_gallery_concept
    FOREIGN KEY (gallery_id, concept_folder_id)
    REFERENCES public.concept_folders(gallery_id, id) ON DELETE CASCADE;

ALTER TABLE public.photo_category_assignments
    ADD CONSTRAINT fk_photo_category_assignments_gallery_detail
    FOREIGN KEY (gallery_id, detail_folder_id)
    REFERENCES public.detail_folders(gallery_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_photo_category_assignments_gallery_photo
    FOREIGN KEY (gallery_id, photo_id)
    REFERENCES public.photos(gallery_id, id) ON DELETE CASCADE;

ALTER TABLE public.categorization_job_photos
    ADD CONSTRAINT fk_categorization_job_photos_gallery_job
    FOREIGN KEY (gallery_id, job_id)
    REFERENCES public.categorization_jobs(gallery_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_categorization_job_photos_gallery_photo
    FOREIGN KEY (gallery_id, photo_id)
    REFERENCES public.photos(gallery_id, id) ON DELETE CASCADE;

ALTER TABLE public.photo_selection_items
    ADD CONSTRAINT fk_photo_selection_items_gallery_selection
    FOREIGN KEY (gallery_id, selection_id)
    REFERENCES public.photo_selections(gallery_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_photo_selection_items_gallery_photo
    FOREIGN KEY (gallery_id, photo_id)
    REFERENCES public.photos(gallery_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_photo_selection_items_added_by_user
    FOREIGN KEY (added_by_user_id)
    REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.collab_photo_comments ALTER COLUMN participant_id SET NOT NULL;
ALTER TABLE public.collab_photo_likes ALTER COLUMN participant_id SET NOT NULL;
DROP INDEX IF EXISTS public.uk_collab_photo_likes_active_session_photo_participant;
CREATE UNIQUE INDEX uk_collab_photo_likes_active_session_photo_participant
    ON public.collab_photo_likes (collab_session_id, photo_id, participant_id)
    WHERE deleted_at IS NULL;
ALTER TABLE public.collab_photo_comments
    ADD CONSTRAINT fk_collab_photo_comments_participant
    FOREIGN KEY (participant_id) REFERENCES public.collab_participants(id) ON DELETE CASCADE;
ALTER TABLE public.collab_photo_likes
    ADD CONSTRAINT fk_collab_photo_likes_participant
    FOREIGN KEY (participant_id) REFERENCES public.collab_participants(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS public.uk_collab_photo_likes_active_session_photo_guest;
ALTER TABLE public.collab_photo_comments DROP CONSTRAINT IF EXISTS fk_collab_photo_comments_guest;
ALTER TABLE public.collab_photo_likes DROP CONSTRAINT IF EXISTS fk_collab_photo_likes_guest;
ALTER TABLE public.collab_photo_comments DROP COLUMN IF EXISTS collab_guest_id;
ALTER TABLE public.collab_photo_likes DROP COLUMN IF EXISTS collab_guest_id;
DROP TABLE IF EXISTS public.collab_guests;
