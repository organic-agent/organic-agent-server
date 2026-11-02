-- 구버전 공개·관리자 API의 감사 테이블 접근을 중단한 배포 창에서 적용한다.
-- 이 마이그레이션을 적용하면 기존 감사 기록과 복원 payload는 영구 삭제된다.
DROP TABLE admin_audit_logs;
DROP TABLE admin_entity_revisions;
DROP TABLE admin_auth_events;

DROP FUNCTION prevent_admin_audit_log_mutation();
DROP FUNCTION prevent_admin_entity_revision_mutation();
