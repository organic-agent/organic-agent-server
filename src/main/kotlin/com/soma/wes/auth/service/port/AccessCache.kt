package com.soma.wes.auth.service.port

import com.soma.wes.auth.dto.AccessCacheKeyDto

/**
 * 권한 확인에 쓰는, 거의 바뀌지 않는 값의 캐시(#286 (k)) — 계정 상태·작업공간 종류·스튜디오 정지 여부. 요청마다 같은 행을 다시 읽지 않게 한다.
 *
 * 값은 TTL([com.soma.wes.auth.config.AccessCacheProperties.ttl]) 동안 믿는다. 이 서버에서 일어나는 변경(탈퇴)은 커밋 뒤 [evict]로 바로 지우고,
 * 다른 프로세스의 변경(관리자 정지)은 TTL로만 반영된다. 서버가 여러 대가 되면 탈퇴도 다른 서버에는 TTL로만 닿는다 — 그때 공유 저장소(Redis)
 * 어댑터를 더해 설정으로 바꾼다. 꺼져 있으면 빈이 없고 호출자는 매번 DB를 본다.
 */
interface AccessCache {
    /** TTL 안의 값이 있으면 그것을, 없으면 [load]로 읽어 담고 돌려준다. null도 값으로 담는다(없는 계정·지워진 작업공간). */
    fun <T> get(key: AccessCacheKeyDto, load: () -> T): T

    fun evict(keys: Collection<AccessCacheKeyDto>)
}
