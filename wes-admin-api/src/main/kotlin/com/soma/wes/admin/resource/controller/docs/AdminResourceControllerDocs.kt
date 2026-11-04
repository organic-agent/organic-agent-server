package com.soma.wes.admin.resource.controller.docs

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.domain.AdminRetouchArtifactType
import com.soma.wes.admin.resource.dto.request.AdminPhotoAccessRequest
import com.soma.wes.admin.resource.dto.request.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.request.AdminRetouchArtifactAccessRequest
import com.soma.wes.admin.resource.dto.request.AdminWorkflowRequest
import com.soma.wes.admin.resource.dto.request.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.request.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.request.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.dto.response.AdminAnalysisFailuresResponse
import com.soma.wes.admin.resource.dto.response.AdminChildTrashResponse
import com.soma.wes.admin.resource.dto.response.AdminObservabilityLinksResponse
import com.soma.wes.admin.resource.dto.response.AdminOperationsOverviewResponse
import com.soma.wes.admin.resource.dto.response.AdminPhotoAccessResponse
import com.soma.wes.admin.resource.dto.response.AdminReprocessResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceContextResponse
import com.soma.wes.admin.resource.dto.response.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceResponse
import com.soma.wes.admin.resource.dto.response.AdminRetouchArtifactAccessResponse
import com.soma.wes.admin.resource.dto.response.AdminSystemSettingsResponse
import com.soma.wes.admin.resource.dto.response.AdminTrashBatchResponse
import com.soma.wes.admin.resource.dto.response.AdminWorkflowResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity

@Tag(name = "Admin Resource", description = "최고 관리자의 운영 데이터 조회·변경·휴지통·재처리·워크플로. 변경은 조회한 version을 expectedVersion으로 받아 낙관적으로 막고, 대리보기 중에는 쓰기 요청이 403(ADMIN_403_2)이다.")
interface AdminResourceControllerDocs {
    @Operation(summary = "운영 개요", description = "종류별 리소스 수, 운영 이슈, 최근 실패한 작업, 휴지통 대기 수를 한 번에 읽는다. 읽기만 한다.")
    fun getOperationsOverview(loginUser: AdminLoginUser): ResponseEntity<AdminOperationsOverviewResponse>

    @Operation(summary = "관측 링크", description = "correlationId(요청 trace id)로 Grafana Explore·Loki 조회 링크를 만든다. 앞뒤 공백을 지운 값이 소문자 16진수 16자가 아니면 400(ADMIN_400_6)이다. Grafana·Loki 주소가 설정되지 않은 쪽 링크는 null이다. 검색식은 관리자 앱 운영 로그(service=wes-admin-api, env=prod)로 고정돼 있다.")
    fun observabilityLinks(loginUser: AdminLoginUser, correlationId: String): ResponseEntity<AdminObservabilityLinksResponse>

    @Operation(summary = "연쇄 삭제 휴지통 목록", description = "연쇄 삭제 배치를 ACTIVE·PURGING·나머지 순, 그 안에서는 최근 삭제 순으로 최대 200개 돌려준다. restorable은 ACTIVE이고 복구 기한 전일 때만 true다.")
    fun listTrash(loginUser: AdminLoginUser): ResponseEntity<List<AdminTrashBatchResponse>>

    @Operation(summary = "하위 데이터 휴지통 목록", description = "공유폴더 댓글·좋아요와 보정 항목처럼 부모 아래에서 따로 지운 하위 데이터 기록이다. ACTIVE·PURGING·PURGE_FAILED는 모두, 그 밖의 이력은 최근 500개까지 돌려준다.")
    fun listChildTrash(loginUser: AdminLoginUser): ResponseEntity<List<AdminChildTrashResponse>>

    @Operation(summary = "휴지통 배치 복원", description = "배치에 묶인 데이터를 한 번에 되살린다. version 확인은 하지 않는다. 배치가 없으면 404(ADMIN_404_6), 정리(PURGING)가 시작됐거나 복구 기한이 지났으면 410(ADMIN_410_2), ACTIVE가 아니거나 되살린 행 수가 배치 기록과 다르면 409(ADMIN_409_10)이다. 사진이 루트인 배치는 갤러리 사진 한도를 다시 지킨다.")
    fun restoreTrashBatch(loginUser: AdminLoginUser, batchId: Long, servletRequest: HttpServletRequest): ResponseEntity<AdminTrashBatchResponse>

    @Operation(summary = "리소스 검색", description = "query는 대소문자를 무시한 부분 일치이고, types를 생략하면 모든 종류에서 찾는다. 최근 수정 순으로 돌려주며 삭제 여부는 deleted로 표시한다. page가 0보다 작거나 size가 1~100 밖이면 400(ADMIN_400_6)이다.")
    fun search(loginUser: AdminLoginUser, query: String?, types: Set<AdminResourceType>?, page: Int, size: Int, servletRequest: HttpServletRequest): ResponseEntity<AdminResourcePageResponse>

    @Operation(summary = "리소스 상세", description = "종류와 id로 상세와 현재 version을 읽는다. 없으면 404(ADMIN_404_4)이다.")
    fun get(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceResponse>

    @Operation(summary = "리소스 맥락", description = "상세에 연관 리소스·사실·섹션을 붙여 읽는다. trashBatchId는 이 리소스를 루트나 하위로 품은 활성 배치이고, canRestoreDirectly는 이 리소스가 루트인 ACTIVE 배치가 복구 기한 안일 때만 true다. 리소스가 없으면 404(ADMIN_404_4)이다.")
    fun getContext(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceContextResponse>

    @Operation(summary = "사진 원본 접근", description = "삭제되지 않은 사진에만 서명 URL을 발급하고, 없으면 404(ADMIN_404_4)이다. PREVIEW는 미리보기가 없으면 원본으로 대신하지 않고 404다 — 목록 한 번에 원본 여러 장을 서명·로딩하지 않게 하려는 것이다. 저장 키는 응답에 담지 않는다.")
    fun accessOriginalPhoto(loginUser: AdminLoginUser, id: Long, request: AdminPhotoAccessRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminPhotoAccessResponse>

    @Operation(summary = "보정 산출물 접근", description = "삭제되지 않은 보정 회차의 항목에 해당 산출물(ANNOTATION=주석 이미지, RESULT=보정 결과)이 연결돼 있을 때만 서명 URL을 발급하고, 아니면 404(ADMIN_404_4)이다. 파일명은 완료된 관리자 업로드 기록의 원본 이름을 쓰고 없으면 만든 이름을 쓴다. 저장 키는 응답에 담지 않는다.")
    fun accessRetouchArtifact(loginUser: AdminLoginUser, roundId: Long, retouchPhotoId: Long, artifactType: AdminRetouchArtifactType, request: AdminRetouchArtifactAccessRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminRetouchArtifactAccessResponse>

    @Operation(summary = "리소스 생성", description = "리소스 정의가 생성을 허용하는 종류만 만든다. 생성 불가 종류, 정의에 없거나 생성에서 받지 않는 키, 필수 필드 누락, DB 무결성 위반은 400(ADMIN_400_6)이다. USER를 만들면 개인 작업공간을, GALLERY를 만들면 셀렉 행을 함께 만든다. STUDIO에 지정한 사용자가 없으면 404(ADMIN_404_4)이다.")
    fun create(loginUser: AdminLoginUser, type: AdminResourceType, request: CreateAdminResourceRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceResponse>

    @Operation(summary = "리소스 수정", description = "바꿀 필드만 받는다. expectedVersion이 현재 version과 다르거나 그 사이 바뀌었으면 409(ADMIN_409_5)이다. 수정 불가 종류, 정의에 없거나 수정이 막힌 키, 빈 맵, DB 무결성 위반은 400(ADMIN_400_6)이다. 공유폴더를 revoked=false로 되살리면 링크 토큰을 새로 만들고, 보정 요청의 status만 바꾸면 제품의 회차 전이 규칙을 따른다.")
    fun update(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: UpdateAdminResourceRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceResponse>

    @Operation(summary = "리소스 삭제", description = "PHOTO_CATEGORY_ASSIGNMENT·PHOTO_RATING은 휴지통 없이 바로 지운다. USER·STUDIO·GALLERY·PHOTO·CONCEPT_FOLDER·DETAIL_FOLDER·SELECTION·COLLABORATION·RETOUCH_REQUEST는 하위 데이터까지 연쇄 삭제 배치로 휴지통에 옮겨 보존 기간 동안 복원할 수 있게 한다. 그 밖의 종류는 409(ADMIN_409_9)이다. 스튜디오의 마지막 OWNER인 사용자는 409(ADMIN_409_15), 이미 삭제됐거나 겹치는 활성 배치·정리 작업과 부딪치면 409(ADMIN_409_10), version이 다르면 409(ADMIN_409_5)이다. 사용자를 지우면 그 사용자의 리프레시 토큰을 지우고, 배치에 걸린 대기 작업을 취소한다.")
    fun delete(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: ChangeAdminResourceStateRequest, servletRequest: HttpServletRequest): ResponseEntity<Unit>

    @Operation(summary = "리소스 복원", description = "이 리소스가 루트인 활성 배치를 통째로 되살린다. 다른 배치의 하위로만 들어가 있으면 409(ADMIN_409_14)로 막고 루트 배치에서 복원하게 한다. 배치가 없으면 예전 방식으로 지운 GALLERY·PHOTO만 보존 기간 안에서 되살리고, 그 밖은 404(ADMIN_404_6)이다. 연쇄 삭제 루트가 될 수 없는 종류는 409(ADMIN_409_6), 정리가 시작됐거나 기한이 지났으면 410(ADMIN_410_2), version이 다르면 409(ADMIN_409_5)이다. 사진 복원은 갤러리 사진 한도를 다시 지킨다.")
    fun restore(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: ChangeAdminResourceStateRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceResponse>

    @Operation(summary = "리소스 정지", description = "USER·STUDIO만 정지할 수 있고 그 밖은 409(ADMIN_409_11)이다. 삭제된 대상이거나 이미 정지돼 있으면 409(ADMIN_409_12), version이 다르면 409(ADMIN_409_5)이다. 정지하면 사용자 본인(STUDIO는 모든 멤버)의 리프레시 토큰을 지운다.")
    fun suspend(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: ChangeAdminResourceStateRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceResponse>

    @Operation(summary = "리소스 정지 해제", description = "USER·STUDIO만 다시 활성화할 수 있고 그 밖은 409(ADMIN_409_11)이다. 삭제된 대상이거나 정지돼 있지 않으면 409(ADMIN_409_12), version이 다르면 409(ADMIN_409_5)이다. 정지 때 지운 리프레시 토큰은 되살리지 않는다.")
    fun activate(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: ChangeAdminResourceStateRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminResourceResponse>

    @Operation(summary = "갤러리 분석 재처리", description = "분석 데이터를 리셋해 파이프라인이 다시 돌게 한다 — Lambda를 직접 부르지 않고, 살아 있는 분석 잡이 없으면 하나 만들어 폴더 물질화까지 잇는다. GALLERY만 받고 그 밖은 400(ADMIN_400_6), 멱등 키 형식이 틀리면 400(ADMIN_400_7)이다. 갤러리가 없으면 404(ADMIN_404_4), 삭제됐거나 version이 다르면 409(ADMIN_409_5), 임베더 실행이 설정되지 않았으면 503(PHOTO_503_1)이다. FAILED_ONLY는 실패 사진이 없으면 409(ADMIN_409_18), categorize 중이면 409(ADMIN_409_19)이다. 같은 키로 완료된 요청은 accepted=false로 앞선 대상 수를 돌려주고, 완료되지 않은 같은 키는 409(ADMIN_409_8), 다른 요청에 쓴 키는 409(ADMIN_409_7)이다.")
    fun reprocess(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: AdminReprocessRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminReprocessResponse>

    @Operation(summary = "갤러리 분석 실패 사진", description = "분석이 결정적으로 실패한 사진과 사유를 돌려준다 — FAILED_ONLY 재처리가 되살릴 대상이다. 사유는 photo_analysis.error 그대로라 서버가 쓴 값과 AI 실행기가 쓴 값이 섞인다. 갤러리가 없거나 삭제됐으면 404(ADMIN_404_4)이다.")
    fun getAnalysisFailures(loginUser: AdminLoginUser, id: Long, servletRequest: HttpServletRequest): ResponseEntity<AdminAnalysisFailuresResponse>

    @Operation(summary = "운영 워크플로 실행", description = "confirm=true가 아니면 실행하지 않고 400(ADMIN_400_6)이다. 액션마다 허용 대상 종류와 fields 키가 정해져 있고 어긋나면 400(ADMIN_400_6), 멱등 키 형식이 틀리면 400(ADMIN_400_7)이다. 대상이 없으면 404(ADMIN_404_4), version이 다르거나 삭제됐으면 409(ADMIN_409_5)이다(하위 복원 액션은 부모 version으로 확인). 같은 키로 완료된 요청은 replayed=true로 앞선 결과를 돌려주고 업로드 URL은 새로 서명한다. 완료되지 않은 같은 키는 409(ADMIN_409_8), 다른 요청에 쓴 키는 409(ADMIN_409_7)이다. 서명 URL은 멱등 결과에 저장하지 않는다.")
    fun executeWorkflow(loginUser: AdminLoginUser, type: AdminResourceType, id: Long, request: AdminWorkflowRequest, servletRequest: HttpServletRequest): ResponseEntity<AdminWorkflowResponse>

    @Operation(summary = "시스템 설정", description = "세션·잠금·휴지통 보존·업로드·서명 URL TTL 같은 실행 설정을 읽기만 한다. 비밀 값은 담지 않고 외부 연동은 설정 여부만 알린다.")
    fun getSystemSettings(loginUser: AdminLoginUser): ResponseEntity<AdminSystemSettingsResponse>
}
