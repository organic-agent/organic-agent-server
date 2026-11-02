-- 관리자 셀렉 이력 참조를 제거한 관리자 API와 함께 전환하는 배포에서 적용한다.
-- 현재 선택·보정 회차 항목과 AI 초안 및 AI 작업의 결과 참조는 유지한다.
ALTER TABLE retouch_rounds DROP COLUMN selection_revision_id;

DELETE FROM admin_selection_revisions WHERE source = 'ADMIN';
