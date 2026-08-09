-- V9에서 NOT VALID로 설치한 외래키는 새 쓰기를 즉시 보호한다. 별도 트랜잭션인 V10에서
-- 기존 행을 검사해, 고아 데이터가 있으면 배포를 중단한다.

ALTER TABLE galleries VALIDATE CONSTRAINT fk_galleries_studio;
ALTER TABLE gallery_members VALIDATE CONSTRAINT fk_gallery_members_gallery;
ALTER TABLE gallery_invites VALIDATE CONSTRAINT fk_gallery_invites_gallery;
ALTER TABLE photos VALIDATE CONSTRAINT fk_photos_gallery;
ALTER TABLE photo_folders VALIDATE CONSTRAINT fk_photo_folders_gallery;
ALTER TABLE photo_folder_items VALIDATE CONSTRAINT fk_photo_folder_items_folder;
ALTER TABLE photo_folder_items VALIDATE CONSTRAINT fk_photo_folder_items_photo;
ALTER TABLE photo_selections VALIDATE CONSTRAINT fk_photo_selections_gallery;
ALTER TABLE photo_selection_items VALIDATE CONSTRAINT fk_photo_selection_items_selection;
ALTER TABLE photo_selection_items VALIDATE CONSTRAINT fk_photo_selection_items_photo;
ALTER TABLE photo_ratings VALIDATE CONSTRAINT fk_photo_ratings_photo;
