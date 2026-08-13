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

    /** 요청에 이 갤러리의 사진이 아닌 id가 섞여 있는 경우. */
    PHOTO_NOT_IN_GALLERY(HttpStatus.BAD_REQUEST, "COLLAB_400_3", "이 갤러리의 사진이 아닙니다."),

    /**
     * 아직 업로드가 끝나지 않은(PENDING) 사진을 담으려는 경우.
     *
     * 실체가 없는 사진을 담으면 하객 화면에는 깨진 이미지가 뜬다. 작가는 그것이 올라오는
     * 중이라는 뜻임을 알지만 하객은 알 도리가 없다.
     */
    PHOTO_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "COLLAB_400_4", "아직 업로드가 끝나지 않은 사진은 담을 수 없습니다."),

    /** 한 요청에서 다룰 수 있는 사진 수(`app.storage.max-batch-size`)를 넘긴 경우. */
    TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST, "COLLAB_400_5", "한 번에 처리할 수 있는 사진 수를 넘었습니다."),

    /** 사진 id가 하나도 없는 경우. 빈 요청을 성공시키면 화면은 담긴 줄 안다. */
    EMPTY_PHOTO_IDS(HttpStatus.BAD_REQUEST, "COLLAB_400_6", "사진을 하나 이상 지정해야 합니다."),

    // COLLAB_400_7, COLLAB_400_8은 페이지 번호·크기 검증용이었으나
    // com.soma.wes.global.page.PageRequests가 거절 대신 절삭하도록 바뀌며 사라졌다.
    // 뒤 번호를 당기면 살아 있는 코드의 계약이 바뀌므로 구멍을 그대로 둔다.

    /** 세션 이름이 비었거나 100자를 넘긴 경우. */
    INVALID_SESSION_NAME(
        HttpStatus.BAD_REQUEST,
        "COLLAB_400_9",
        "협업 세션 이름은 1자 이상 100자 이하여야 합니다.",
    ),

    /**
     * 세션을 열 때 지정한 폴더가 이 갤러리의 것이 아니거나 없는 경우.
     *
     * 400이다. 폴더가 없다는 사실을 404로 알려주면 다른 갤러리에 어떤 폴더 id가 있는지를
     * 응답으로 되짚을 수 있다.
     */
    FOLDER_NOT_IN_GALLERY(HttpStatus.BAD_REQUEST, "COLLAB_400_10", "이 갤러리의 폴더가 아닙니다."),

    /**
     * 사진이 하나도 없는 폴더로 세션을 열려는 경우.
     *
     * 조용히 빈 세션을 만들지 않는다. 폴더를 골랐다는 것은 그 사진들을 물어보겠다는 뜻이라,
     * 아무것도 담기지 않은 링크를 성공으로 돌려주면 부부는 그것을 그대로 하객에게 보낸다.
     * 빈 세션이 필요하면 folderId 없이 열면 된다.
     */
    EMPTY_FOLDER(HttpStatus.BAD_REQUEST, "COLLAB_400_11", "사진이 없는 폴더로는 협업 세션을 열 수 없습니다."),

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

    /** 발급한 적 없는 협업 토큰이거나, 그 세션이 이 갤러리의 것이 아닌 경우. */
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_404_1", "존재하지 않는 협업 링크입니다."),

    /** 이 세션에 담기지 않은 사진. */
    COLLAB_PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_404_2", "협업 세션에 없는 사진입니다."),

    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_404_3", "존재하지 않는 댓글입니다."),

    /**
     * 이미 담긴 사진을 또 담으려는 경우.
     *
     * 조용히 건너뛰지 않는다. 신랑과 신부가 각자의 화면에서 담는 물건이라 "이미 담겨 있다"는
     * 사실 자체가 필요한 정보다 — 선택 앨범이 같은 판단을 한다.
     */
    PHOTO_ALREADY_ADDED(HttpStatus.CONFLICT, "COLLAB_409_1", "이미 협업 세션에 담긴 사진입니다."),

    /**
     * 부부가 거둬들인 링크로 들어온 경우.
     *
     * 410이다. 우리가 발급한 링크가 맞고 지금은 쓸 수 없다는 뜻이라, "그런 링크 없음"(404)과
     * 구분해야 하객에게 "새 링크를 받으세요"를 안내할 수 있다.
     */
    SESSION_REVOKED(HttpStatus.GONE, "COLLAB_410_1", "더 이상 사용할 수 없는 협업 링크입니다."),
}
