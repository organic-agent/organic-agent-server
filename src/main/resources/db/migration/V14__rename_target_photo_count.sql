-- 계약 장수 컬럼의 이름을 동작에 맞춘다.
--
-- `target`은 목표치로 읽히는데 실제 강제는 상한이다(넘으면 거절, 미달은 허용). 게다가
-- `target_photo_count`가 `selected_count`·`remaining_count`와 나란히 놓이면 셋 다 "지금 장수"로
-- 읽혀 하나만 성격이 다르다는 것이 드러나지 않는다.
--
-- **이 rename은 롤링 배포에 안전하지 않다.** 구버전 서버는 `target_photo_count`를 읽고 쓰므로
-- 두 버전이 겹치는 창에서는 없는 컬럼을 참조한다. 배포 시 겹치는 창을 두지 않는다.
ALTER TABLE galleries
    RENAME COLUMN target_photo_count TO max_selectable_photo_count;
