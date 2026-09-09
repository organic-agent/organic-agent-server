ALTER TABLE public.users ADD COLUMN profile_image_url varchar(2048);
ALTER TABLE public.gallery_invites ADD COLUMN issued_by_user_id bigint
    REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE public.studio_invites ADD COLUMN issued_by_user_id bigint
    REFERENCES public.users(id) ON DELETE SET NULL;
CREATE INDEX idx_gallery_invites_issuer ON public.gallery_invites(issued_by_user_id);
CREATE INDEX idx_studio_invites_issuer ON public.studio_invites(issued_by_user_id);
