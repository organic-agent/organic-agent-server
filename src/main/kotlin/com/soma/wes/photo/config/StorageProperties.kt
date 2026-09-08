package com.soma.wes.photo.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 원본 사진이 사는 곳과, 그곳으로 가는 서명 URL의 수명.
 *
 * [bucket]만 배포 인프라에 종속되어 Parameter Store에서 온다. 나머지는 바뀌면 사용자 경험이
 * 바뀌는 정책 값이라 저장소(`config/application-variable.yml`)에 둔다.
 */
@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    /**
     * 환경마다 버킷이 다르다(prod는 운영 버킷, local은 인프라 `module.storage_dev`의 dev 버킷).
     * 그래서 객체 키에는 환경 구분자가 없고 `galleries/{galleryId}/`부터 시작한다.
     */
    val bucket: String,

    /** 업로드용 서명 URL의 수명. 프론트가 수천 장을 순차 업로드하는 동안 만료되면 안 된다. */
    val uploadUrlTtl: Duration,

    /** 조회용 서명 URL의 수명. 목록을 다시 부르면 새 URL이 나오므로 업로드보다 짧게 잡는다. */
    val viewUrlTtl: Duration,

    /**
     * 원본 조회용 서명 URL의 수명. [viewUrlTtl]과 따로 두는 것은 화면의 성격이 달라서다.
     *
     * 목록은 스크롤하며 지나가지만 상세는 한 장을 오래 열어둔다. 목록과 같은 15분으로
     * 서명하면 확대해 보는 도중에 만료되고, 그때 S3는 403을 돌려주므로 사용자에게는
     * 사진이 깨진 것처럼 보인다.
     */
    val originalUrlTtl: Duration,

    /**
     * 한 요청에서 발급할 URL 개수이자 한 번에 담을 수 있는 사진 수.
     *
     * 목록 조회의 페이지 크기 상한은 [com.soma.wes.global.page.PageRequests.MAX_SIZE]가 따로
     * 맡는다. 원래 이 값 하나가 두 정책을 겸했는데, 배치 상한인 1000이 페이지 크기 상한으로도
     * 쓰여 `?size=1000` 요청 하나가 서명 URL 1000개를 만들 수 있었다.
     */
    val maxBatchSize: Int,

    /**
     * 원본 한 장의 바이트 상한. 프론트가 리사이즈를 끝낸 뒤 실제 크기로 URL을 받고, 그 값이 서명에 들어가므로
     * 서버는 발급 때 이 상한만 보면 된다 — 초과 크기의 객체는 S3가 서명 불일치로 거절한다.
     */
    val maxUploadBytes: Long,

    /**
     * 완료 통보 없이 남은 PENDING 사진을 서버가 스스로 확인하는 규칙. 프론트가 죽어도 S3에 올라간 원본은
     * 갤러리에 나타나야 한다.
     * - [pendingFirstCheckAfter]: 발급 뒤 이 시간이 지나면 처음 HeadObject 로 확인한다.
     * - [pendingRecheckEvery]: 아직 없으면 이 간격으로 다시 본다(방치된 행 하나가 하루에 수백 번 호출을 만들지 않게).
     * - [pendingGiveUpAfter]: 발급 뒤 이 시간이 지나도 없으면 휴지통으로 보낸다. 탭을 다시 열어 이어 올리는 재개를 고려해 넉넉히 둔다.
     */
    val pendingFirstCheckAfter: Duration,
    val pendingRecheckEvery: Duration,
    val pendingGiveUpAfter: Duration,
)
