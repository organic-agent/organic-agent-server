package com.soma.wes.admin.resource.dto

import com.soma.wes.admin.resource.domain.AdminResourceType

/**
 * 관리자 워크플로의 서버 단일 계약이다.
 *
 * BackOffice는 이 목록과 동일한 target/field 조합만 전송한다. 알 수 없는 필드를 조용히
 * 버리면 오타가 성공 응답으로 보일 수 있으므로 서버도 정확히 같은 계약을 검증한다.
 */
enum class AdminWorkflowAction(
    val allowedTargetTypes: Set<AdminResourceType>,
    val requiredFields: Set<String> = emptySet(),
    val optionalFields: Set<String> = emptySet(),
    val atLeastOneOf: Set<String> = emptySet(),
) {
    TERMINATE_USER_SESSIONS(setOf(AdminResourceType.USER)),
    SET_STUDIO_OWNER(setOf(AdminResourceType.STUDIO), setOf("userId")),
    ADD_STUDIO_MEMBER(setOf(AdminResourceType.STUDIO), setOf("userId")),
    REMOVE_STUDIO_MEMBER(setOf(AdminResourceType.STUDIO), setOf("memberId")),
    ADD_GALLERY_MEMBER(setOf(AdminResourceType.GALLERY), setOf("userId")),
    REMOVE_GALLERY_MEMBER(setOf(AdminResourceType.GALLERY), setOf("memberId")),
    UPDATE_GALLERY_STATES(
        setOf(AdminResourceType.GALLERY),
        setOf("publicStatus", "workflowStatus"),
        setOf("selectionDeadline"),
    ),
    RUN_CATEGORIZATION(setOf(AdminResourceType.GALLERY)),
    REISSUE_GALLERY_INVITE(
        setOf(AdminResourceType.GALLERY),
        optionalFields = setOf("kind", "maxUses", "expiresAt"),
    ),
    REVOKE_GALLERY_INVITE(setOf(AdminResourceType.GALLERY), setOf("inviteId")),
    REISSUE_COLLAB_LINK(setOf(AdminResourceType.COLLABORATION), optionalFields = setOf("ttlSeconds")),
    REVOKE_COLLAB_LINK(setOf(AdminResourceType.COLLABORATION)),
    REOPEN_GALLERY(setOf(AdminResourceType.GALLERY), setOf("selectionDeadline")),
    SUBMIT_GALLERY(setOf(AdminResourceType.GALLERY)),
    COMPLETE_GALLERY(setOf(AdminResourceType.GALLERY)),
    ISSUE_PHOTO_REPLACEMENT(setOf(AdminResourceType.PHOTO), setOf("fileName", "contentType")),
    COMPLETE_PHOTO_REPLACEMENT(setOf(AdminResourceType.PHOTO), setOf("replacementId")),
    RETRY_PROCESSING_JOB(setOf(AdminResourceType.PHOTO, AdminResourceType.SELECTION), setOf("jobId")),
    CANCEL_PROCESSING_JOB(setOf(AdminResourceType.PHOTO, AdminResourceType.SELECTION), setOf("jobId")),
    RESEND_NOTIFICATION(
        AdminResourceType.entries.toSet(),
        setOf("notificationType"),
    ),
    CREATE_AI_SELECTION_DRAFT(setOf(AdminResourceType.SELECTION), optionalFields = setOf("requestedCount")),
    RETRY_AI_SELECTION_JOB(
        setOf(AdminResourceType.SELECTION),
        setOf("jobId"),
        setOf("requestedCount"),
    ),
    CANCEL_AI_SELECTION_JOB(setOf(AdminResourceType.SELECTION), setOf("jobId")),
    REPLACE_SELECTION_ITEMS(setOf(AdminResourceType.SELECTION), setOf("photoIds")),
    SUBMIT_SELECTION_REVISION(setOf(AdminResourceType.SELECTION), optionalFields = setOf("revisionId")),
    WITHDRAW_SELECTION(setOf(AdminResourceType.SELECTION)),
    CREATE_COLLAB_COMMENT(
        setOf(AdminResourceType.COLLABORATION),
        setOf("photoId", "guestId", "content"),
    ),
    UPDATE_COLLAB_COMMENT(
        setOf(AdminResourceType.COLLABORATION),
        setOf("commentId", "commentExpectedVersion", "content"),
    ),
    DELETE_COLLAB_COMMENT(
        setOf(AdminResourceType.COLLABORATION),
        setOf("commentId", "commentExpectedVersion"),
    ),
    RESTORE_COLLAB_COMMENT(
        setOf(AdminResourceType.COLLABORATION),
        setOf("commentId", "commentExpectedVersion"),
    ),
    ADD_COLLAB_LIKE(setOf(AdminResourceType.COLLABORATION), setOf("photoId", "guestId")),
    REMOVE_COLLAB_LIKE(
        setOf(AdminResourceType.COLLABORATION),
        setOf("photoId", "guestId", "likeExpectedVersion"),
    ),
    RESTORE_COLLAB_LIKE(
        setOf(AdminResourceType.COLLABORATION),
        setOf("likeId", "likeExpectedVersion"),
    ),
    CREATE_ALBUM_TEMPLATE(setOf(AdminResourceType.ALBUM), setOf("name", "layout")),
    UPDATE_ALBUM_TEMPLATE(
        setOf(AdminResourceType.ALBUM),
        setOf("templateId", "templateExpectedVersion", "name", "layout"),
    ),
    DELETE_ALBUM_TEMPLATE(
        setOf(AdminResourceType.ALBUM),
        setOf("templateId", "templateExpectedVersion"),
    ),
    RESTORE_ALBUM_TEMPLATE(
        setOf(AdminResourceType.ALBUM),
        setOf("templateId", "templateExpectedVersion"),
    ),
    REPLACE_ALBUM_LAYOUT(
        setOf(AdminResourceType.ALBUM),
        setOf("folders"),
        setOf("templateId", "selectionRevisionId"),
    ),
    CREATE_RETOUCH_ITEM(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf("photoId"),
        setOf("requestText", "structuredAiMetadata"),
    ),
    UPDATE_RETOUCH_ITEM(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf("retouchPhotoId", "retouchPhotoExpectedVersion"),
        setOf("requestText", "annotationKey", "resultKey", "resultContentType", "structuredAiMetadata"),
        setOf("requestText", "annotationKey", "resultKey", "resultContentType", "structuredAiMetadata"),
    ),
    ISSUE_RETOUCH_ARTIFACT_UPLOAD(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf(
            "retouchPhotoId",
            "retouchPhotoExpectedVersion",
            "artifactType",
            "fileName",
            "contentType",
        ),
    ),
    COMPLETE_RETOUCH_ARTIFACT_UPLOAD(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf("uploadId", "retouchPhotoId", "retouchPhotoExpectedVersion"),
    ),
    DELETE_RETOUCH_ITEM(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf("retouchPhotoId", "retouchPhotoExpectedVersion"),
    ),
    RESTORE_RETOUCH_ITEM(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf("retouchPhotoId", "retouchPhotoExpectedVersion"),
    ),
    UPDATE_RETOUCH_DELIVERY(
        setOf(AdminResourceType.RETOUCH_REQUEST),
        setOf("consented", "delivered"),
        setOf("deliveryNote", "selectionRevisionId"),
    ),
    SET_STUDIO_RETOUCH_CAPABILITY(
        setOf(AdminResourceType.STUDIO),
        setOf("capability", "enabled"),
    ),
}
