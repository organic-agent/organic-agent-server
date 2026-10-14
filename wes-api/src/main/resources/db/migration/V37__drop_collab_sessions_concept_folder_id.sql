-- V36으로 모든 공유폴더가 컨셉과 연결을 끊었다. 연결을 가리키던 컬럼과 그 제약(유일·FK)을 지운다.
ALTER TABLE collab_sessions DROP CONSTRAINT IF EXISTS uk_collab_sessions_concept_folder;
ALTER TABLE collab_sessions DROP CONSTRAINT IF EXISTS fk_collab_sessions_concept;
ALTER TABLE collab_sessions DROP COLUMN concept_folder_id;
