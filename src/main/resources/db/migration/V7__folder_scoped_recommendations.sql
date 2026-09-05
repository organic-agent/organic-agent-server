-- 폴더 범위 AI 추천.
--
-- 추천 잡이 세부폴더 하나만 대상으로 돌 수 있다(detail_folder_id). 그 잡은 요청 시점에 그 폴더에 든
-- 사진의 기존 추천만 지우고 다시 쓴다 — 다른 폴더의 추천과, 그 뒤 다른 폴더로 옮겨 간 사진의
-- 추천은 그대로 남는다. 화면은 "사진마다 가장 최근 추천"을 읽는다(라운드 전체 교체가 아니다).
ALTER TABLE public.ai_selection_jobs ADD COLUMN detail_folder_id BIGINT;
ALTER TABLE ONLY public.ai_selection_jobs
    ADD CONSTRAINT fk_ai_selection_jobs_detail_folder
    FOREIGN KEY (detail_folder_id) REFERENCES public.detail_folders(id) ON DELETE SET NULL;
CREATE INDEX idx_ai_recommendations_selection_photo ON public.ai_recommendations (selection_id, photo_id);
