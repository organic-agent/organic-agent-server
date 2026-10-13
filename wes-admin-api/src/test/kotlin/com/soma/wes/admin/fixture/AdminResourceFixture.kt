package com.soma.wes.admin.fixture

import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.support.TestSequence
import org.springframework.stereotype.Component

/** 공개 API 빈을 로드하지 않는 관리자 앱에서 현재 리소스 계약으로 배경 데이터를 만든다. */
@Component
class AdminResourceFixture(private val resources: AdminResourceService) {
    fun 사용자(actorAdminId: Long): AdminResourceResponse = resources.create(
        actorAdminId = actorAdminId,
        type = AdminResourceType.USER,
        sourceAddress = null,
        request = CreateAdminResourceRequest(reason = "[TEST_OPERATION] 배경 계정", fields = mapOf(
            "provider" to "KAKAO", "providerId" to "coupon-fixture-${TestSequence.next()}", "nickname" to "쿠폰 사용자",
        )),
    )

    fun 갤러리(actorAdminId: Long, ownerUserId: Long): AdminResourceResponse {
        val studio = resources.create(actorAdminId = actorAdminId, type = AdminResourceType.STUDIO, sourceAddress = null,
            request = CreateAdminResourceRequest(reason = "[TEST_OPERATION] 배경 스튜디오", fields = mapOf(
                "ownerUserId" to ownerUserId, "name" to "쿠폰 스튜디오", "galleryUrl" to "coupon-fixture-${TestSequence.next()}",
            )),
        )
        return resources.create(actorAdminId = actorAdminId, type = AdminResourceType.GALLERY, sourceAddress = null,
            request = CreateAdminResourceRequest(reason = "[TEST_OPERATION] 배경 갤러리", fields = mapOf(
                "workspaceId" to studio.id, "title" to "쿠폰 갤러리",
            )),
        )
    }
}
