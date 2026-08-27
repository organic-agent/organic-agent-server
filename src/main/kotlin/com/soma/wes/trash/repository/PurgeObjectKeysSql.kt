package com.soma.wes.trash.repository

/**
 * 삭제 대상 행이 가리키는 S3 key 중 다른 DB 행이 공유하지 않는 key만 고른다.
 * predicate는 이 패키지의 고정 쿼리 조각만 전달하며 각각 photos p, retouch_photos rp를 쓴다.
 */
fun purgeObjectKeysSql(
    photoPredicate: String,
    retouchPhotoPredicate: String,
): String =
    """
    WITH target_photos AS MATERIALIZED (
        SELECT p.id
        FROM photos p
        WHERE $photoPredicate
    ), target_retouch_photos AS MATERIALIZED (
        SELECT rp.id
        FROM retouch_photos rp
        WHERE $retouchPhotoPredicate
    ), outside_storage_keys AS NOT MATERIALIZED (
        SELECT other.storage_key
        FROM photos other
        WHERE NOT EXISTS (SELECT 1 FROM target_photos target WHERE target.id = other.id)
        UNION ALL
        SELECT other.storage_key
        FROM admin_photo_revisions other
        WHERE NOT EXISTS (SELECT 1 FROM target_photos target WHERE target.id = other.photo_id)
        UNION ALL
        SELECT other.storage_key
        FROM admin_photo_replacement_uploads other
        WHERE NOT EXISTS (SELECT 1 FROM target_photos target WHERE target.id = other.photo_id)
    ), outside_reserved_keys AS NOT MATERIALIZED (
        SELECT storage_key FROM outside_storage_keys
        UNION ALL
        SELECT 'previews/' || regexp_replace(storage_key, '\.[^.]*$', '') || '.jpg'
        FROM outside_storage_keys
        UNION ALL
        SELECT other.preview_key
        FROM photos other
        WHERE NOT EXISTS (SELECT 1 FROM target_photos target WHERE target.id = other.id)
        UNION ALL
        SELECT other.preview_key
        FROM admin_photo_revisions other
        WHERE NOT EXISTS (SELECT 1 FROM target_photos target WHERE target.id = other.photo_id)
        UNION ALL
        SELECT other.annotation_key
        FROM retouch_photos other
        WHERE NOT EXISTS (SELECT 1 FROM target_retouch_photos target WHERE target.id = other.id)
        UNION ALL
        SELECT other.result_key
        FROM retouch_photos other
        WHERE NOT EXISTS (SELECT 1 FROM target_retouch_photos target WHERE target.id = other.id)
        UNION ALL
        SELECT other.storage_key
        FROM admin_retouch_artifact_uploads other
        WHERE NOT EXISTS (
            SELECT 1 FROM target_retouch_photos target WHERE target.id = other.retouch_photo_id
        )
    ), storage_keys AS (
        SELECT p.storage_key
        FROM photos p
        JOIN target_photos target ON target.id = p.id
        UNION
        SELECT r.storage_key
        FROM admin_photo_revisions r
        JOIN target_photos target ON target.id = r.photo_id
        UNION
        SELECT u.storage_key
        FROM admin_photo_replacement_uploads u
        JOIN target_photos target ON target.id = u.photo_id
    ), unshared_storage_keys AS (
        SELECT candidate.storage_key
        FROM storage_keys candidate
        WHERE NOT EXISTS (
            SELECT 1
            FROM outside_reserved_keys outside
            WHERE outside.storage_key = candidate.storage_key
        )
    ), direct_keys AS (
        SELECT storage_key FROM unshared_storage_keys
        UNION
        SELECT p.preview_key
        FROM photos p
        JOIN target_photos target ON target.id = p.id
        UNION
        SELECT r.preview_key
        FROM admin_photo_revisions r
        JOIN target_photos target ON target.id = r.photo_id
        UNION
        SELECT rp.annotation_key
        FROM retouch_photos rp
        JOIN target_retouch_photos target ON target.id = rp.id
        UNION
        SELECT rp.result_key
        FROM retouch_photos rp
        JOIN target_retouch_photos target ON target.id = rp.id
        UNION
        SELECT u.storage_key
        FROM admin_retouch_artifact_uploads u
        JOIN target_retouch_photos target ON target.id = u.retouch_photo_id
    ), candidate_keys AS (
        SELECT storage_key FROM direct_keys
        UNION
        SELECT 'previews/' || regexp_replace(storage_key, '\.[^.]*$', '') || '.jpg'
        FROM unshared_storage_keys
    )
    SELECT candidate.storage_key
    FROM candidate_keys candidate
    WHERE candidate.storage_key IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM outside_reserved_keys outside
          WHERE outside.storage_key = candidate.storage_key
      )
    ORDER BY candidate.storage_key
    """.trimIndent()
