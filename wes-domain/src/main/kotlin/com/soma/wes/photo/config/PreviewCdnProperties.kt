package com.soma.wes.photo.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 부부·작가의 미리보기를 CloudFront 고정 주소로 내주는 손잡이(#336 ADR-0001).
 *
 * 꺼져 있으면 지금처럼 S3 서명 URL을 내준다 — 되돌리기는 [enabled] 하나다. 켜면 미리보기 주소는 만료가 없는 고정 주소가 되고,
 * 접근 권한은 주소가 아니라 갤러리 경로에만 맞는 짧게 사는 서명 쿠키가 진다. 그래서 주소가 새어도 쿠키 없이는 403이다.
 *
 * [baseUrl]·[keyPairId]는 인프라가 만든 값이라 Parameter Store에서, [privateKey]는 SecureString에서 온다.
 */
@ConfigurationProperties(prefix = "app.storage.preview-cdn")
data class PreviewCdnProperties(
    val enabled: Boolean = false,

    /** 이미지 전용 주소. 웹과 같은 상위 도메인이어야 SameSite=Lax 쿠키가 실린다(운영 `https://img.easyselect.kr`). */
    val baseUrl: String = "",

    /**
     * 경로 앞에 붙이는 환경 접두(dev `d`, 운영 빈 값). dev와 운영 쿠키가 같은 상위 도메인에 놓여도 경로가 달라 서로 덮어쓰지 않게 한다.
     * 접두는 CloudFront가 원점으로 보내기 전에 떼어 낸다.
     */
    val pathPrefix: String = "",

    /** CloudFront 키 그룹에 올린 공개키의 ID. */
    val keyPairId: String = "",

    /** 서명 개인키(PKCS#8 PEM). 상태 파일에 남지 않게 Terraform 밖에서 만들어 SecureString에 둔다. */
    val privateKey: String = "",

    /** Set-Cookie의 Domain. API(`api.…`)가 이미지 주소(`img.…`)에 쿠키를 주려면 둘의 상위 도메인이어야 한다. */
    val cookieDomain: String = "",

    /** 쿠키 수명. 권한이 빠진 사람이 계속 볼 수 있는 시간의 상한이기도 하다(QA-5 ≤ 25분). */
    val cookieTtl: Duration = Duration.ofMinutes(20),

    /** 웹이 쿠키를 다시 받는 간격. 수명보다 충분히 짧아야 탭이 잠깐 숨었다 와도 끊기지 않는다. */
    val refreshAfter: Duration = Duration.ofMinutes(10),
)
