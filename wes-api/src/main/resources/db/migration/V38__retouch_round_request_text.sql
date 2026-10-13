-- 회차의 모든 사진에 적용되는 보정 요청. 사진 단위 요청(retouch_photos.request_text)과 같이 TEXT이고
-- 길이 상한은 도메인(RetouchPhoto.MAX_REQUEST_TEXT_LENGTH)이 지킨다.
ALTER TABLE retouch_rounds ADD COLUMN request_text TEXT;
