-- 하객 반응을 3단계(GOOD·SOSO·BAD)에서 좋아요 하나로 줄인다.
--
-- reaction 열이 사라지면 "행이 있다 = 눌렀다"가 이 표의 전부가 되므로, 표(vote)라는 이름도
-- 좋아요(like)로 함께 바꾼다. 좋아요가 아니었던 표(SOSO·BAD)는 옮길 자리가 없어 지운다 —
-- "그저 그렇다"를 좋아요로 승격하면 그 수를 보고 사진을 고르는 부부가 속는다.
DELETE FROM collab_photo_votes WHERE reaction <> 'GOOD';

ALTER TABLE collab_photo_votes DROP CONSTRAINT ck_collab_photo_votes_reaction;
ALTER TABLE collab_photo_votes DROP COLUMN reaction;

ALTER TABLE collab_photo_votes RENAME TO collab_photo_likes;

ALTER TABLE collab_photo_likes
    RENAME CONSTRAINT uk_collab_photo_votes_photo_guest TO uk_collab_photo_likes_photo_guest;
ALTER TABLE collab_photo_likes
    RENAME CONSTRAINT fk_collab_photo_votes_collab_photo TO fk_collab_photo_likes_collab_photo;
ALTER TABLE collab_photo_likes
    RENAME CONSTRAINT fk_collab_photo_votes_collab_guest TO fk_collab_photo_likes_collab_guest;
ALTER TABLE collab_photo_likes
    RENAME CONSTRAINT collab_photo_votes_pkey TO collab_photo_likes_pkey;

ALTER INDEX idx_collab_photo_votes_collab_photo_id RENAME TO idx_collab_photo_likes_collab_photo_id;
