package com.soma.wes.folder.support

import com.soma.wes.folder.service.DetailFolderMergePurgeService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 되돌릴 시간이 지난 세부 폴더 합치기를 주기적으로 정리한다. 절차는 [DetailFolderMergePurgeService.purgeExpired]에 있다.
 * 인스턴스마다 따로 돌아 여러 대면 중복 실행되지만, 이미 지운 행의 DELETE는 아무것도 하지 않아 결과는 같다.
 */
@Component
class DetailFolderMergePurgeScheduler(
    private val purgeService: DetailFolderMergePurgeService,
) {

    /** 매시 50분. 다른 정리 작업(정각 · 15 · 30 · 40 · 45분)과 겹치지 않게 둔다. 숨은 행은 보이지 않으니 급할 이유가 없다. */
    @Scheduled(cron = "0 50 * * * *")
    fun purge() {
        purgeService.purgeExpired()
    }
}
