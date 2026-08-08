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

    /** 한 요청에서 발급할 URL 개수이자 목록 조회 페이지 크기의 상한. */
    val maxBatchSize: Int,
)
