package com.soma.wes.admin.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class AdminErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_USERNAME(HttpStatus.BAD_REQUEST, "ADMIN_400_1", "관리자 아이디 형식이 올바르지 않습니다."),
    INVALID_DISPLAY_NAME(HttpStatus.BAD_REQUEST, "ADMIN_400_2", "관리자 이름 형식이 올바르지 않습니다."),
    PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "ADMIN_400_3", "비밀번호는 12자 이상 128자 이하여야 합니다."),
    INVALID_REASON(HttpStatus.BAD_REQUEST, "ADMIN_400_4", "변경 사유를 입력해 주세요."),
    INVALID_AUDIT_RANGE(HttpStatus.BAD_REQUEST, "ADMIN_400_5", "감사 로그 조회 기간이 올바르지 않습니다."),
    INVALID_RESOURCE_FIELDS(HttpStatus.BAD_REQUEST, "ADMIN_400_6", "관리자 리소스 필드가 올바르지 않습니다."),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "ADMIN_400_7", "멱등성 키 형식이 올바르지 않습니다."),
    INVALID_IMPERSONATION_TARGET(HttpStatus.BAD_REQUEST, "ADMIN_400_8", "대리보기는 사용자·스튜디오·갤러리만 지원합니다."),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "ADMIN_401_1", "아이디 또는 비밀번호가 올바르지 않습니다."),
    SESSION_INVALID(HttpStatus.UNAUTHORIZED, "ADMIN_401_2", "관리자 세션이 만료되었거나 유효하지 않습니다."),

    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "ADMIN_403_1", "정지된 관리자 계정입니다."),
    IMPERSONATION_READ_ONLY(HttpStatus.FORBIDDEN, "ADMIN_403_2", "읽기 전용 대리보기에서는 쓰기 요청을 실행할 수 없습니다."),

    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_404_1", "관리자 계정을 찾을 수 없습니다."),
    AUDIT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_404_2", "감사 로그를 찾을 수 없습니다."),
    REVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_404_3", "데이터 리비전을 찾을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_404_4", "관리할 데이터를 찾을 수 없습니다."),
    IMPERSONATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN_404_5", "활성 대리보기 세션을 찾을 수 없습니다."),

    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "ADMIN_409_1", "이미 사용 중인 관리자 아이디입니다."),
    PASSWORD_REUSE(HttpStatus.CONFLICT, "ADMIN_409_2", "현재 비밀번호와 다른 비밀번호를 사용해 주세요."),
    SELF_TEMPORARY_PASSWORD_FORBIDDEN(HttpStatus.CONFLICT, "ADMIN_409_3", "본인의 비밀번호는 비밀번호 변경 화면에서 변경해 주세요."),
    REVISION_RESTORE_UNSUPPORTED(HttpStatus.CONFLICT, "ADMIN_409_4", "이 리비전은 안전하게 복원할 수 없습니다."),
    RESOURCE_VERSION_CONFLICT(HttpStatus.CONFLICT, "ADMIN_409_5", "데이터가 다른 요청에 의해 변경되었습니다. 최신 값을 다시 조회해 주세요."),
    RESOURCE_RESTORE_UNSUPPORTED(HttpStatus.CONFLICT, "ADMIN_409_6", "이 데이터는 현재 복원할 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "ADMIN_409_7", "같은 멱등성 키가 다른 요청에 사용되었습니다."),
    REPROCESS_ALREADY_REQUESTED(HttpStatus.CONFLICT, "ADMIN_409_8", "같은 재처리 요청이 이미 접수되었습니다."),
    RESOURCE_DELETE_UNSUPPORTED(HttpStatus.CONFLICT, "ADMIN_409_9", "이 데이터는 휴지통 삭제를 지원하지 않습니다."),

    REVISION_EXPIRED(HttpStatus.GONE, "ADMIN_410_1", "7일 복구 기간이 지난 리비전입니다."),

    ACCOUNT_LOCKED(HttpStatus.LOCKED, "ADMIN_423_1", "로그인 실패 횟수를 초과해 계정이 잠겼습니다."),

    PASSWORD_HASH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "ADMIN_500_1", "관리자 비밀번호를 안전하게 처리하지 못했습니다."),
}
