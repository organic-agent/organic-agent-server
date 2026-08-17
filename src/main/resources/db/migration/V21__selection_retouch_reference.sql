-- 보정본을 최종 선택 앨범에 담는 연동. 선택 항목이 보정본을 참조한다.
--
-- photo_id는 항상 원본이다. 보정본으로 담으면 retouch_photo_id가 채워지고, 납품(앨범) 조회가
-- 그 항목을 결과 key로 서명한다. 이렇게 두면 중복·정원 규칙이 원본 photo_id 기준으로 그대로
-- 동작한다 -- 같은 컷을 원본과 보정본으로 두 번 담는 것이 기존 유니크
-- (selection_id, photo_id)로 자동 차단된다.
--
-- ON DELETE SET NULL: 보정 항목이 사라져도 부부가 그 컷을 골랐다는 사실은 남아야 한다 --
-- 참조만 지우고 원본 항목으로 되돌린다. (현재 보정 항목은 원본 사진·갤러리 삭제로만 사라지고
-- 그때는 선택 항목도 함께 지워지므로, 이 규칙은 미래의 삭제 경로에 대한 안전망이다.)
ALTER TABLE photo_selection_items
    ADD COLUMN retouch_photo_id BIGINT REFERENCES retouch_photos (id) ON DELETE SET NULL;

-- 보정 항목 삭제(원본 사진 휴지통 비우기의 CASCADE)가 참조 항목을 찾을 때 전체 스캔을 막는다.
CREATE INDEX idx_photo_selection_items_retouch_photo_id
    ON photo_selection_items (retouch_photo_id);
