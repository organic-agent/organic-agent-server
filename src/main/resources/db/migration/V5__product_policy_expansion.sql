-- Product policy expansion.
-- V1~V4 are already deployed. This migration is additive and keeps every legacy
-- column required by the V4 application so the public API can be rolled back.

-- Gallery-owned category and selection references. Keep these nullable until
-- the validation gate in the contract migration.
ALTER TABLE public.detail_folders ADD COLUMN IF NOT EXISTS gallery_id bigint;
UPDATE public.detail_folders detail
SET gallery_id = concept.gallery_id
FROM public.concept_folders concept
WHERE detail.concept_folder_id = concept.id AND detail.gallery_id IS NULL;

ALTER TABLE public.photo_category_assignments ADD COLUMN IF NOT EXISTS gallery_id bigint;
UPDATE public.photo_category_assignments assignment
SET gallery_id = detail.gallery_id
FROM public.detail_folders detail
WHERE assignment.detail_folder_id = detail.id AND assignment.gallery_id IS NULL;

ALTER TABLE public.categorization_job_photos
    ADD COLUMN IF NOT EXISTS gallery_id bigint,
    ADD COLUMN IF NOT EXISTS status character varying(20) DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS failure_code character varying(80),
    ADD COLUMN IF NOT EXISTS processed_at timestamp with time zone;
UPDATE public.categorization_job_photos item
SET gallery_id = job.gallery_id,
    status = CASE
        WHEN item.status IS NOT NULL AND item.status <> 'PENDING' THEN item.status
        WHEN job.status = 'FAILED' THEN 'FAILED'
        WHEN EXISTS (
            SELECT 1 FROM public.photo_category_assignments assignment
            WHERE assignment.photo_id = item.photo_id AND assignment.gallery_id = job.gallery_id
        ) THEN 'ASSIGNED'
        WHEN job.status = 'SUCCEEDED' THEN 'UNCLASSIFIED'
        ELSE 'PENDING'
    END,
    failure_code = CASE WHEN job.status = 'FAILED' THEN job.failure_code ELSE item.failure_code END,
    processed_at = CASE WHEN job.status <> 'RUNNING' THEN COALESCE(job.completed_at, CURRENT_TIMESTAMP) ELSE item.processed_at END
FROM public.categorization_jobs job
WHERE item.job_id = job.id AND item.gallery_id IS NULL;

ALTER TABLE public.photo_selection_items
    ADD COLUMN IF NOT EXISTS gallery_id bigint,
    ADD COLUMN IF NOT EXISTS added_by_user_id bigint,
    ADD COLUMN IF NOT EXISTS sort_order integer;
UPDATE public.photo_selection_items item
SET gallery_id = selection.gallery_id
FROM public.photo_selections selection
WHERE item.selection_id = selection.id AND item.gallery_id IS NULL;
UPDATE public.photo_selection_items item
SET added_by_user_id = COALESCE(
        gallery.created_by_user_id,
        (SELECT member.user_id
         FROM public.workspace_members member
         WHERE member.workspace_id = gallery.workspace_id
           AND member.role = 'OWNER' AND member.deleted_at IS NULL
         ORDER BY member.id LIMIT 1)
    )
FROM public.photo_selections selection
JOIN public.galleries gallery ON gallery.id = selection.gallery_id
WHERE item.selection_id = selection.id AND item.added_by_user_id IS NULL;
WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY selection_id ORDER BY created_at NULLS LAST, id) - 1 AS next_order
    FROM public.photo_selection_items
)
UPDATE public.photo_selection_items item
SET sort_order = ordered.next_order
FROM ordered
WHERE item.id = ordered.id AND item.sort_order IS NULL;

INSERT INTO public.photo_selections
    (gallery_id, status, version, created_at, updated_at)
SELECT gallery.id, 'SELECTING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM public.galleries gallery
WHERE gallery.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM public.photo_selections selection WHERE selection.gallery_id = gallery.id)
ON CONFLICT (gallery_id) DO NOTHING;

ALTER TABLE public.categorization_job_photos
    DROP CONSTRAINT IF EXISTS ck_categorization_job_photos_status;
ALTER TABLE public.categorization_job_photos
    ADD CONSTRAINT ck_categorization_job_photos_status
    CHECK (status IN ('PENDING', 'ASSIGNED', 'UNCLASSIFIED', 'FAILED'));

ALTER TABLE public.photos ADD CONSTRAINT uk_photos_gallery_id_id UNIQUE (gallery_id, id);
ALTER TABLE public.concept_folders ADD CONSTRAINT uk_concept_folders_gallery_id_id UNIQUE (gallery_id, id);
ALTER TABLE public.detail_folders ADD CONSTRAINT uk_detail_folders_gallery_id_id UNIQUE (gallery_id, id);
ALTER TABLE public.categorization_jobs ADD CONSTRAINT uk_categorization_jobs_gallery_id_id UNIQUE (gallery_id, id);
ALTER TABLE public.photo_selections ADD CONSTRAINT uk_photo_selections_gallery_id_id UNIQUE (gallery_id, id);

CREATE INDEX IF NOT EXISTS idx_detail_folders_gallery ON public.detail_folders (gallery_id, id);
CREATE INDEX IF NOT EXISTS idx_photo_category_assignments_gallery ON public.photo_category_assignments (gallery_id, photo_id);
CREATE INDEX IF NOT EXISTS idx_categorization_job_photos_gallery ON public.categorization_job_photos (gallery_id, job_id);
CREATE INDEX IF NOT EXISTS idx_photo_selection_items_gallery ON public.photo_selection_items (gallery_id, selection_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_photo_selection_items_selection_sort_order
    ON public.photo_selection_items (selection_id, sort_order) WHERE sort_order IS NOT NULL;

-- Unified collaboration identity. The legacy guest table and foreign-key
-- columns remain for the V4/V5 rollback window.
CREATE TABLE IF NOT EXISTS public.collab_participants (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    collab_session_id bigint NOT NULL,
    participant_type character varying(20) NOT NULL,
    user_id bigint,
    guest_token character varying(255),
    nickname character varying(50) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    CONSTRAINT ck_collab_participants_type CHECK (participant_type IN ('USER', 'GUEST')),
    CONSTRAINT ck_collab_participants_identity CHECK (
        (participant_type = 'USER' AND user_id IS NOT NULL AND guest_token IS NULL)
        OR (participant_type = 'GUEST' AND user_id IS NULL AND guest_token IS NOT NULL)
    ),
    CONSTRAINT fk_collab_participants_session FOREIGN KEY (collab_session_id)
        REFERENCES public.collab_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_collab_participants_user FOREIGN KEY (user_id)
        REFERENCES public.users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_collab_participants_guest_token
    ON public.collab_participants (guest_token) WHERE guest_token IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_collab_participants_session_user
    ON public.collab_participants (collab_session_id, user_id)
    WHERE participant_type = 'USER' AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_collab_participants_session
    ON public.collab_participants (collab_session_id, id);

INSERT INTO public.collab_participants
    (collab_session_id, participant_type, user_id, guest_token, nickname, version, created_at, updated_at)
SELECT guest.collab_session_id, 'GUEST', NULL, guest.guest_token, guest.nickname,
       guest.version, guest.created_at, guest.updated_at
FROM public.collab_guests guest
ON CONFLICT (guest_token) WHERE guest_token IS NOT NULL DO NOTHING;

ALTER TABLE public.collab_photo_comments ADD COLUMN IF NOT EXISTS participant_id bigint;
ALTER TABLE public.collab_photo_likes ADD COLUMN IF NOT EXISTS participant_id bigint;
UPDATE public.collab_photo_comments comment
SET participant_id = participant.id
FROM public.collab_guests guest
JOIN public.collab_participants participant
  ON participant.guest_token = guest.guest_token
WHERE comment.collab_guest_id = guest.id AND comment.participant_id IS NULL;
UPDATE public.collab_photo_likes like_row
SET participant_id = participant.id
FROM public.collab_guests guest
JOIN public.collab_participants participant
  ON participant.guest_token = guest.guest_token
WHERE like_row.collab_guest_id = guest.id AND like_row.participant_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_collab_photo_comments_participant
    ON public.collab_photo_comments (participant_id, id);
CREATE INDEX IF NOT EXISTS idx_collab_photo_likes_participant
    ON public.collab_photo_likes (participant_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_collab_photo_likes_active_session_photo_participant
    ON public.collab_photo_likes (collab_session_id, photo_id, participant_id)
    WHERE deleted_at IS NULL AND participant_id IS NOT NULL;
