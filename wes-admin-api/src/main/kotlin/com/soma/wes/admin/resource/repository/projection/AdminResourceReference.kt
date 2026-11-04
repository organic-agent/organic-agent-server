package com.soma.wes.admin.resource.repository.projection

import com.soma.wes.admin.resource.domain.AdminResourceType

data class AdminResourceReference(val type: AdminResourceType, val id: Long)
