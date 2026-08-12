-- 하객이 들고 오는 값의 이름을 코드와 맞춘다.
--
-- 이 개념을 기획과 화면 문구는 일관되게 "협업 링크"라고 부르는데 코드와 스키마만 "공유
-- 토큰"이라 불러 왔다. 한 개념에 두 단어가 붙어 있으면 대화에서 매번 같은 것인지 확인해야
-- 한다. 값도 의미도 그대로이고 이름만 바꾼다.
--
-- V12를 고치지 않고 새 마이그레이션으로 두는 이유는 V12가 이미 돌아간 데이터베이스가 있기
-- 때문이다. 파일을 고치면 체크섬이 어긋나 그쪽은 부팅 자체가 막힌다.
ALTER TABLE collab_sessions RENAME COLUMN share_token TO collab_token;

ALTER TABLE collab_sessions
    RENAME CONSTRAINT uk_collab_sessions_share_token TO uk_collab_sessions_collab_token;
