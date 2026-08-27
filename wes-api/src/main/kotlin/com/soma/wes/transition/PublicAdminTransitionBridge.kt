package com.soma.wes.transition

/**
 * 1차 모듈 분리 릴리스의 공개 API 호환 브리지 marker.
 *
 * 별도 `wes-admin-api`와 BackOffice의 내부 upstream 전환이 운영에서 검증될 때까지
 * 공개 artifact에도 관리자 endpoint와 최신 관리자 security chain을 함께 싣는다.
 * 두 번째 cleanup PR은 이 annotation, `wes-api`의 admin source include, 그리고 관련
 * boundary test를 한 번에 제거해야 한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PublicAdminTransitionBridge {
    companion object {
        const val REMOVAL_MARKER =
            "WES_PUBLIC_ADMIN_TRANSITION_BRIDGE_REMOVE_AFTER_BACKOFFICE_CUTOVER"
    }
}
