-- 공유폴더는 컨셉·세부 폴더와 따로 사는 폴더다(#248). 컨셉을 따라가던 공유폴더를 지금 하객에게 보이는 사진으로
-- 고정하고 연결을 끊는다. 이후 사진을 옮기거나 컨셉을 지워도 공유폴더와 하객 반응은 그대로 남는다.
--
-- 휴지통의 사진도 담는다 — 수동 공유폴더는 휴지통 사진을 숨겼다가 복원하면 다시 보여 준다(컨셉 연결 때와 같다).
-- 휴지통의 컨셉·세부 폴더에 있던 사진은 지금 보이지 않으므로 담지 않는다.
INSERT INTO collab_session_photos (collab_session_id, gallery_id, photo_id, version, created_at, updated_at)
SELECT DISTINCT s.id, s.gallery_id, a.photo_id, 0, now(), now()
FROM collab_sessions s
JOIN concept_folders c ON c.id = s.concept_folder_id AND c.gallery_id = s.gallery_id AND c.deleted_at IS NULL
JOIN detail_folders d ON d.concept_folder_id = c.id AND d.deleted_at IS NULL
JOIN detail_folder_assignments a ON a.detail_folder_id = d.id
JOIN photos p ON p.id = a.photo_id AND p.gallery_id = s.gallery_id AND p.status <> 'PENDING'
WHERE s.concept_folder_id IS NOT NULL
ON CONFLICT (collab_session_id, photo_id) DO NOTHING;

-- 관리자 편집의 낙관적 잠금이 이 변경을 알아보도록 버전도 올린다.
UPDATE collab_sessions
SET concept_folder_id = NULL, version = version + 1, updated_at = now()
WHERE concept_folder_id IS NOT NULL;
