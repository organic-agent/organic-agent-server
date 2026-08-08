-- 사진 한 장에 딸린 촬영 정보(EXIF).
--
-- 채우는 것은 이 서버가 아니라 임베딩 Lambda다. 이미지 바이트가 서버를 거치지 않는다는
-- 제약이 그대로 여기에도 걸리는데, Lambda는 이미 원본을 받아 HEIC까지 디코딩해 들고 있다.
-- embedding·preview_key를 쓰는 바로 그 UPDATE에 얹으면 되므로, 서버에 이미지 처리
-- 의존성(EXIF 파서)을 들이지 않고 값을 얻는 유일한 길이다.
--
-- **전부 nullable이다.** 비어 있는 이유가 둘이라 하나만 생각하면 안 된다:
--   1. 아직 Lambda가 돌지 않은 사진(PENDING·UPLOADED)에는 값이 있을 수 없다.
--   2. EXIF가 아예 없는 파일도 있다 -- 스크린샷이나, 편집 도구가 메타데이터를 떼고
--      저장한 파일이 그렇다. 이때는 크기와 바이트 수만 채워진다.
-- 그래서 NULL은 "값이 없다"까지만 말한다. 왜 없는지는 status가 말한다.
ALTER TABLE photos
    -- 촬영 시각에 타임존을 붙이지 않는다(TIMESTAMPTZ가 아니다). EXIF의 DateTimeOriginal에는
    -- 오프셋이 없고 카메라가 그 순간의 벽시계를 그대로 적을 뿐이다. 서버 타임존으로 해석해
    -- 넣으면 신혼여행지에서 찍은 사진이 조용히 9시간 옮겨가고, 그 사실은 어디에도 남지 않는다.
    -- 찍힌 그대로 두고 해석은 화면에 맡긴다. created_at(=업로드 시각)과 다른 타입인 것은
    -- 실수가 아니라 이 차이를 드러내는 것이다.
    ADD COLUMN taken_at      TIMESTAMP(6),
    ADD COLUMN camera_make   VARCHAR(100),
    ADD COLUMN camera_model  VARCHAR(100),
    -- 셔터 속도는 EXIF에서 유리수(1/200초)로 온다. 초 단위 실수로 바꿔 담으면 0.005가 되어
    -- 화면에 다시 "1/200"로 되돌릴 때 반올림 오차가 붙는다. 사람이 읽는 값이므로 온 그대로 둔다.
    ADD COLUMN exposure_time VARCHAR(30),
    ADD COLUMN f_number      DOUBLE PRECISION,
    ADD COLUMN iso           INTEGER,
    -- 원본의 가로·세로. EXIF 회전을 반영한 뒤의 값이라 사람이 보는 방향과 같다.
    ADD COLUMN width         INTEGER,
    ADD COLUMN height        INTEGER,
    ADD COLUMN byte_size     BIGINT;
