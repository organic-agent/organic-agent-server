package com.soma.wes.gallery.domain

/**
 * 초대 링크가 지금 쓸 수 있는 상태인지.
 *
 * 컬럼이 아니라 [GalleryInvite.statusAt]이 계산해 내놓는 값이다. 저장하면 만료를 정각에
 * 옮겨 적는 작업이 필요해지고, 그게 밀린 동안 만료된 링크가 유효한 것으로 보인다.
 *
 * [REVOKED]가 [EXPIRED]보다 앞선다 — 폐기된 링크가 나중에 만료 시각까지 지나도 작가에게는
 * "내가 거둬들인 링크"로 보여야 한다.
 */
enum class GalleryInviteStatus {

    /** 지금 누르면 들어와진다. */
    ACTIVE,

    /** 기한이 지났다. 작가가 새로 발급해주면 되는 상태다. */
    EXPIRED,

    /** 작가가 거둬들였다. */
    REVOKED,
}
