-- 세부 폴더 컷 종류(cut_type) 제거 — 읽는 곳이 없는 값이다.
--
-- AI 폴더를 만들 때 사진 피사체 다수결로 한 번 채웠지만, 웹·관리자·추천 어디서도 읽지 않았고 사진을 옮겨도 다시
-- 계산하지 않아 실제 구성과 어긋났다. 코드는 #198 에서 먼저 지웠다(두 앱이 그 코드로 배포된 뒤에만 이 파일을 적용한다 —
-- 옛 코드가 떠 있는 동안 컬럼이 없으면 물질화 INSERT 가 실패한다). 사진별 피사체는 photo_analysis.subjects 가 그대로 말한다.
-- CHECK 제약 ck_detail_folders_cut_type(V23 에서 이름 변경)은 컬럼과 함께 사라진다.
ALTER TABLE public.detail_folders DROP COLUMN cut_type;
