package com.soma.wes.admin.resource.domain

enum class AdminChildTrashType(
    val parentType: AdminResourceType,
) {
    COLLAB_COMMENT(AdminResourceType.COLLABORATION),
    COLLAB_LIKE(AdminResourceType.COLLABORATION),
    ALBUM_TEMPLATE(AdminResourceType.ALBUM),
    RETOUCH_ITEM(AdminResourceType.RETOUCH_REQUEST),
}
