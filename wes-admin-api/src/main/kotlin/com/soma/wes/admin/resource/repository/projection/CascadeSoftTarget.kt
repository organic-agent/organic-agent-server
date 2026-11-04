package com.soma.wes.admin.resource.repository.projection


internal data class CascadeSoftTarget(val type: String, val table: String, val idColumn: String = "id")
