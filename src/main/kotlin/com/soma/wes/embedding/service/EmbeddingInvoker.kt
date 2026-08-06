package com.soma.wes.embedding.service

/**
 * 갤러리 하나의 임베딩 계산을 외부 실행기에 맡긴다.
 *
 * 구현을 갈아끼우려고 인터페이스로 둔 것이 아니다. Lambda SDK 타입이 이 서비스 계층까지
 * 올라오지 않게 막는 것이 목적이다 — 구현은
 * [com.soma.wes.embedding.infrastructure.LambdaEmbeddingInvoker].
 */
interface EmbeddingInvoker {

    /**
     * 실행기가 실제로 붙어 있는지.
     *
     * 로컬·테스트에는 없는 것이 정상이라 기동을 막지 않는다. 대신 부르려는 순간에 걸러낸다.
     */
    val isAvailable: Boolean

    /**
     * 계산을 요청하고, 결과를 기다리지 않고 돌아온다.
     *
     * 갤러리 하나가 15분까지 걸릴 수 있어 동기 호출이 불가능하다. 진행 상황은
     * `GET /photos/summary`의 embedded 수로 확인한다.
     *
     * @throws com.soma.wes.photo.exception.PhotoException 계산이 아니라 호출 자체가 실패한 경우
     */
    fun invoke(galleryId: Long, force: Boolean)
}
