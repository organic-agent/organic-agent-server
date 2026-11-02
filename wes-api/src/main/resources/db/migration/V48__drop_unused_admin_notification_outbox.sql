-- 외부 알림 outbox 참조를 제거한 공개·관리자 API로 함께 전환하는 배포에서 적용한다.
-- 현재 알림 기능은 admin_notification_inbox를 사용하며 기존 outbox 기록은 삭제한다.
DROP TABLE admin_notification_outbox;
