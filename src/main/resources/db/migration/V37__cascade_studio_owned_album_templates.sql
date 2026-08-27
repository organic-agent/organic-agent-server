-- WES-259: 스튜디오 안에서 재사용하는 앨범 템플릿은 그 스튜디오의 전용 데이터다.
-- 갤러리 하나가 사라질 때는 보존하지만, 사용자/스튜디오의 7일 복구 기간이 끝나 실제
-- studio 행이 삭제되면 같은 트랜잭션에서 함께 물리 삭제한다. 다른 studio 템플릿은 FK
-- 대상이 아니므로 영향을 받지 않는다.
ALTER TABLE admin_album_templates
    DROP CONSTRAINT fk_admin_album_templates_studio,
    ADD CONSTRAINT fk_admin_album_templates_studio
        FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE CASCADE;
