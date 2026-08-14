-- 갤러리당 살아 있는 초대 링크는 하나다. 재발급하면 원링크를 폐기한다.
--
-- 인덱스를 걸기 전에 기존 데이터를 정리한다. 지금까지는 issue가 기존 링크를 건드리지 않아
-- 한 갤러리에 유효한 링크가 여럿 있을 수 있고, 그대로면 아래 인덱스 생성이 실패한다.
-- 가장 최근 것만 남기는 이유는 작가가 마지막으로 발급해 전달한 링크가 그것이기 때문이다.
UPDATE gallery_invites
SET revoked_at = now(),
    updated_at = now()
WHERE revoked_at IS NULL
  AND id NOT IN (SELECT max(id)
                 FROM gallery_invites
                 WHERE revoked_at IS NULL
                 GROUP BY gallery_id);

-- 만료(expires_at < now())는 조건에 넣을 수 없다. now()가 immutable이 아니라 부분 인덱스
-- 조건으로 허용되지 않는다. 그래서 이 인덱스가 못 박는 것은 "폐기되지 않은 행 하나"까지이며,
-- 만료된 행이 그 한 자리를 차지한 채 남는다. 재발급이 그것까지 폐기하는 것은 애플리케이션의 몫이다.
CREATE UNIQUE INDEX uk_gallery_invites_active_gallery_id
    ON gallery_invites (gallery_id)
    WHERE revoked_at IS NULL;
