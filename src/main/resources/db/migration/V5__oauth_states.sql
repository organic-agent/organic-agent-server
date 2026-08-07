-- OAuth 왕복 사이에 값을 실어 나르는 왕복표.
--
-- 초대 링크를 누른 사람은 로그인을 마쳐야 갤러리에 들어올 수 있는데, provider로 갔다 오는
-- 동안 초대 토큰을 어딘가 살려둬야 한다. redirect_uri는 provider에 등록된 값과 정확히
-- 같아야 해서 거기에 실을 수 없고, 브라우저 저장소는 카카오톡 인앱 브라우저가 외부
-- 브라우저로 넘어갈 때 끊긴다. 그래서 provider가 그대로 되돌려주는 state를 쓴다.
--
-- state에는 난수만 싣고 초대 토큰은 여기 남긴다. 초대 토큰은 가진 사람이 곧 갤러리 멤버가
-- 되는 자격증명이라, provider 로그와 리다이렉트 기록에 남기면 안 된다.
CREATE TABLE oauth_states
(
    -- 난수 그 자체가 키다. 별도 id를 두면 state로 찾는 유일한 조회에 인덱스가 하나 더 필요하다.
    state        VARCHAR(64) PRIMARY KEY,
    -- 초대와 무관한 일반 로그인도 state를 받으므로 NULL이 정상이다.
    invite_token VARCHAR(255),
    expires_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE,
    updated_at   TIMESTAMP(6) WITH TIME ZONE
);

-- 만료된 행을 걷어낼 때 쓴다. 로그인을 시작만 하고 그만둔 사용자의 행은 소비되지 않아
-- 그대로 남는다.
CREATE INDEX idx_oauth_states_expires_at ON oauth_states (expires_at);
