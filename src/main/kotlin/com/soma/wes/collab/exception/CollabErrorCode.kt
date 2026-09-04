package com.soma.wes.collab.exception

import com.soma.wes.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class CollabErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {

    /** 닉네임이 비었거나 20자를 넘긴 경우. */
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "COLLAB_400_1", "닉네임은 1자 이상 20자 이하여야 합니다."),

    /** 댓글이 비었거나 500자를 넘긴 경우. */
    INVALID_COMMENT(HttpStatus.BAD_REQUEST, "COLLAB_400_2", "댓글은 1자 이상 500자 이하여야 합니다."),

    // COLLAB_400_3~8은 직접 사진을 담던 구 세션과 페이지 검증용이었으나
    // com.soma.wes.global.page.PageRequests가 거절 대신 절삭하도록 바뀌며 사라졌다.
    // 뒤 번호를 당기면 살아 있는 코드의 계약이 바뀌므로 구멍을 그대로 둔다.

    /** 세션 이름이 비었거나 100자를 넘긴 경우. */
    INVALID_SESSION_NAME(
        HttpStatus.BAD_REQUEST,
        "COLLAB_400_9",
        "협업 세션 이름은 1자 이상 100자 이하여야 합니다.",
    ),

    /**
     * 글을 남기려는데 하객 토큰이 없거나 이 세션의 것이 아닌 경우.
     *
     * 401이다. 보는 것은 토큰 없이 되고, 남기는 것만 "당신이 누구인지" 먼저 밝히라는 뜻이라
     * 화면은 이 코드를 보고 닉네임 입력을 띄우면 된다.
     */
    GUEST_NOT_IDENTIFIED(HttpStatus.UNAUTHORIZED, "COLLAB_401_1", "닉네임을 먼저 입력해 주세요."),

    /**
     * 아직 열리지 않은(DRAFT) 갤러리를 협업 링크로 여는 경우.
     *
     * 정상적으로는 생기지 않는다 — 세션을 여는 것 자체가 부부의 동작이라 갤러리가 열려 있어야
     * 한다. 갤러리를 다시 DRAFT로 되돌리는 경로가 생기는 날을 위한 문이다.
     */
    SESSION_NOT_READY(HttpStatus.FORBIDDEN, "COLLAB_403_1", "아직 공개되지 않은 갤러리입니다."),

    /**
     * 갤러리가 마감된 뒤에 하객이 글을 남기려는 경우.
     *
     * 보는 것은 계속 된다. 부부가 이미 고르기를 끝냈다면 새 의견은 쓸 곳이 없지만, 하객이
     * 자기가 남긴 말과 사진을 다시 열어보는 것까지 막을 이유는 없다.
     */
    FEEDBACK_CLOSED(HttpStatus.FORBIDDEN, "COLLAB_403_2", "지금은 의견을 남길 수 없습니다."),

    /** 남이 쓴 댓글을 지우려는 경우. 부부와 담당 작가는 인증된 경로로 지운다. */
    COMMENT_NOT_OWNED(HttpStatus.FORBIDDEN, "COLLAB_403_3", "직접 남긴 댓글만 지울 수 있습니다."),
    PARTICIPANT_READ_ONLY(HttpStatus.FORBIDDEN, "COLLAB_403_4", "이 계정은 협업 내용을 볼 수 있지만 댓글과 좋아요를 남길 수 없습니다."),

    /** 발급한 적 없는 협업 토큰이거나, 그 세션이 이 갤러리의 것이 아닌 경우. */
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_404_1", "존재하지 않는 협업 링크입니다."),

    /** 이 세션에 담기지 않은 사진. */
    COLLAB_PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_404_2", "협업 세션에 없는 사진입니다."),

    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_404_3", "존재하지 않는 댓글입니다."),

    /**
     * 부부가 거둬들인 링크로 들어온 경우.
     *
     * 410이다. 우리가 발급한 링크가 맞고 지금은 쓸 수 없다는 뜻이라, "그런 링크 없음"(404)과
     * 구분해야 하객에게 "새 링크를 받으세요"를 안내할 수 있다.
     */
    SESSION_REVOKED(HttpStatus.GONE, "COLLAB_410_1", "더 이상 사용할 수 없는 협업 링크입니다."),

    /** 운영자가 제한 시간으로 재발급한 링크의 사용 기한이 끝난 경우. */
    SESSION_EXPIRED(HttpStatus.GONE, "COLLAB_410_2", "사용 기한이 만료된 협업 링크입니다."),
}
