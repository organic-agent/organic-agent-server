-- 주기 작업 리더 임대(#336 ADR-0002). Redis(ElastiCache) 키 하나가 하던 일을 행 하나로 옮긴다.
-- 행은 lease_key 하나뿐이고 잃어도 되는 값이다 — 비우면 다음 갱신(10초)에서 누군가 다시 얻는다. FK 없음.
-- 만료 판정은 DB 시각(now())으로 해서 서버 시계 어긋남에 기대지 않는다.
-- 운영 상태라 scripts/reset-test-data.sh 의 TRUNCATE 목록에는 넣지 않는다(비워도 무해하지만 테스트 데이터가 아니다).
CREATE TABLE scheduler_leader_lease (
    lease_key  varchar(64)  PRIMARY KEY,
    holder     varchar(128) NOT NULL,
    expires_at timestamptz  NOT NULL
);
