-- refresh 토큰을 평문 대신 SHA-256 해시로 저장한다(#424).
-- DB 덤프·백업이 새도 저장된 값으로는 재발급을 받을 수 없게 하려는 것이다.
-- 토큰은 서명된 JWT(고엔트로피)라 솔트 없이 해시만으로 충분하다.
--
-- 이미 발급된 토큰은 그 자리에서 해시로 바꾼다. 그래서 배포 때 아무도 다시 로그인하지 않는다.
-- 앱은 JWT 문자열의 UTF-8 바이트를 SHA-256 → 소문자 16진수로 만든다(RefreshToken.hashed). 아래 식과 같은 값이다.

ALTER TABLE refresh_tokens ADD COLUMN token_hash varchar(64);

UPDATE refresh_tokens SET token_hash = encode(sha256(convert_to(token, 'UTF8')), 'hex');

ALTER TABLE refresh_tokens ALTER COLUMN token_hash SET NOT NULL;

ALTER TABLE refresh_tokens DROP COLUMN token;

-- 로그인 한 번이 만든 토큰 묶음. 재발급(rotation)은 묶음을 이어 가고, 새 로그인은 새 묶음을 만든다.
-- 같은 묶음의 교체된 토큰이 다시 오면 복사된 토큰이 쓰인 것으로 본다. 다른 묶음의 옛 토큰은 다른 기기의 재로그인이라 거절만 한다.
-- 이 컬럼이 생기기 전에 발급된 토큰은 묶음을 모르므로 NULL로 둔다(거절만 하는 쪽으로 처리된다).
ALTER TABLE refresh_tokens ADD COLUMN family_id varchar(36);

-- 지금 토큰을 발급한 시각. 교체 직후 다른 탭이 옛 토큰을 들고 오는 경쟁을 재사용과 가르는 데 쓴다.
ALTER TABLE refresh_tokens ADD COLUMN issued_at timestamp(6) with time zone;

UPDATE refresh_tokens SET issued_at = COALESCE(updated_at, created_at, now());

ALTER TABLE refresh_tokens ALTER COLUMN issued_at SET NOT NULL;
