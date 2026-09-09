-- 소속은 알림을 만든 시점의 이력이다. 부모 삭제 후에도 남아야 하므로 FK를 두지 않는다.
ALTER TABLE public.user_notifications ADD COLUMN studio_workspace_id bigint;
UPDATE public.user_notifications SET studio_workspace_id = scope_id WHERE scope = 'STUDIO';
UPDATE public.user_notifications n SET studio_workspace_id = g.workspace_id
FROM public.galleries g JOIN public.workspaces w ON w.id = g.workspace_id AND w.type = 'STUDIO'
WHERE n.scope = 'GALLERY' AND n.scope_id = g.id;
CREATE INDEX idx_user_notifications_studio ON public.user_notifications(user_id, studio_workspace_id, created_at DESC, id DESC);
