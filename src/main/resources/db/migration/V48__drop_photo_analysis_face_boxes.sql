-- photo_analysis.face_boxes 제거.
--
-- V45가 v2 시절 계약({face_count, max_face_ratio})으로 만들어 둔 자리인데, 확정 파이프라인
-- (photoselect_v1)에는 얼굴 검출 단계가 없어 항상 '{}'만 적재됐고 읽는 곳도 없다
-- (이 서버는 애초에 매핑하지 않았다 — PhotoAnalysis.kt 머리말).
-- P1(앨범 크롭 안전)이 바운딩 박스를 쓰게 되면 그때의 계약으로 새 마이그레이션을 만든다.
ALTER TABLE photo_analysis
    DROP COLUMN face_boxes;
