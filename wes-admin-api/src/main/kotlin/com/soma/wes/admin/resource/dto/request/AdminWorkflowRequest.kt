package com.soma.wes.admin.resource.dto.request

import com.soma.wes.admin.resource.domain.AdminWorkflowAction
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class AdminWorkflowRequest(
    @field:Schema(
        description = "실행할 워크플로. 액션마다 허용 대상 종류와 fields의 필수·선택 키가 정해져 있고 어긋나면 400(ADMIN_400_6)이다.",
        example = "REISSUE_GALLERY_INVITE",
    )
    val action: AdminWorkflowAction,
    @field:PositiveOrZero
    @field:Schema(
        description = "대상 리소스를 조회한 version. 다르거나 대상이 삭제됐으면 409(ADMIN_409_5)이다. RESTORE_COLLAB_COMMENT·RESTORE_COLLAB_LIKE·RESTORE_RETOUCH_ITEM은 이 사전 검사 없이 부모 version 확인으로 넘긴다.",
        example = "3",
    )
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    @field:Schema(
        description = "재전송을 한 번으로 묶는 키. 영문·숫자·`._:-` 8~128자이며, 완료된 같은 요청은 앞선 결과를 다시 돌려주고 다른 요청에 다시 쓰면 409(ADMIN_409_7)이다.",
        example = "workflow-20261104-001",
    )
    val idempotencyKey: String,
    @field:Schema(
        description = "운영자 확인 표시. true가 아니면 실행하지 않고 400(ADMIN_400_6)이다.",
        example = "true",
    )
    val confirm: Boolean,
    @field:Schema(
        description = "액션별 입력. REISSUE_GALLERY_INVITE는 kind(STUDIO_MEMBER/GALLERY_MEMBER/PERSONAL_PARTNER), maxUses(1~100), 미래 expiresAt을 선택적으로 받으며 생략한 kind/maxUses는 직전 초대 정책을 유지한다.",
    )
    val fields: Map<String, Any?> = emptyMap(),
)
