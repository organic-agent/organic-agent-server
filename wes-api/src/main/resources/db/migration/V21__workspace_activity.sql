-- 업무 활동은 메타데이터와 별도 저장해 관리자 낙관적 잠금 버전을 증가시키지 않는다.
CREATE TABLE public.gallery_activity (
    gallery_id bigint PRIMARY KEY REFERENCES public.galleries(id) ON DELETE CASCADE,
    last_activity_at timestamptz NOT NULL
);
CREATE TABLE public.workspace_activity (
    workspace_id bigint PRIMARY KEY REFERENCES public.workspaces(id) ON DELETE CASCADE,
    last_activity_at timestamptz NOT NULL
);
