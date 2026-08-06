package com.soma.wes.embedding.service

import com.soma.wes.embedding.config.EmbeddingProperties
import com.soma.wes.embedding.dto.response.EmbeddingRunResponse
import com.soma.wes.gallery.service.GalleryAccessPolicy
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest

/**
 * 갤러리 하나의 임베딩을 계산하라고 Lambda를 깨운다.
 *
 * 벡터를 만드는 일 자체는 이 서버가 하지 않는다 — 이미지 바이트가 여기를 거치지 않는 것과
 * 같은 이유다. 모델 가중치만 수백 MB이고 추론은 CPU를 통째로 먹는다.
 *
 * **장당이 아니라 갤러리 단위로 부른다.** S3 이벤트로 장당 트리거를 걸면 수천 장 업로드가
 * Lambda 수천 개를 동시에 띄우고, 각자 커넥션을 열어 db.t4g.micro를 고갈시킨다.
 *
 * **응답을 기다리지 않는다.** 갤러리 하나가 Lambda 상한인 15분까지 걸릴 수 있어 동기 호출이
 * 불가능하다. 진행 상황은 `GET /photos/summary`의 embedded 수로 확인한다.
 */
// 클래스 단위 @Transactional을 걸지 않는다. 걸면 Lambda 호출이 트랜잭션 안에서 일어나
// 그동안 커넥션 하나를 붙잡고 있게 된다. 여기서 DB를 쓰는 것은 count 하나뿐이라
// 리포지토리 호출 자체의 트랜잭션으로 충분하다.
@Service
class EmbeddingService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val lambdaClient: LambdaClient,
    private val properties: EmbeddingProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(galleryId: Long, userId: Long, force: Boolean): EmbeddingRunResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)

        // 기동이 아니라 여기서 실패한다. 로컬·테스트에는 Lambda가 없는 것이 정상이라
        // 함수 이름이 비어 있다고 앱을 못 뜨게 만들면 개발이 막힌다.
        if (!properties.isConfigured) {
            throw PhotoException(PhotoErrorCode.EMBEDDING_NOT_CONFIGURED)
        }

        // 실제로 무엇을 계산할지는 Lambda가 같은 기준으로 다시 고른다. 여기서 세는 것은
        // 호출자에게 "몇 장이 대상인지"를 즉시 알려주기 위한 값일 뿐이다.
        val targets = if (force) {
            photoRepository.countByGalleryId(galleryId)
        } else {
            photoRepository.countByGalleryIdAndEmbeddingIsNull(galleryId)
        }

        // 페이로드를 문자열로 조립해도 안전한 이유: galleryId는 Long, force는 Boolean이라
        // 사용자 문자열이 끼어들 자리가 없다.
        val payload = """{"galleryId":$galleryId,"force":$force}"""

        val statusCode = try {
            lambdaClient.invoke(
                InvokeRequest.builder()
                    .functionName(properties.functionName)
                    // EVENT는 큐에 넣고 즉시 돌아온다. RequestResponse로 부르면
                    // 15분짜리 작업을 HTTP 요청 하나가 붙들고 기다리게 된다.
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromUtf8String(payload))
                    .build(),
            ).statusCode()
        } catch (e: SdkException) {
            // 계산 실패가 아니라 호출 실패다(권한·스로틀링·함수 없음). 둘을 같은 코드로
            // 돌려주면 "Lambda 로그를 볼 것"과 "IAM을 볼 것"을 구분할 수 없다.
            log.error("임베딩 Lambda 호출 실패: galleryId={}, function={}", galleryId, properties.functionName, e)
            throw PhotoException(PhotoErrorCode.EMBEDDING_INVOCATION_FAILED)
        }

        log.info(
            "임베딩 실행 요청: galleryId={}, force={}, 대상={}장, function={}, status={}",
            galleryId, force, targets, properties.functionName, statusCode,
        )

        return EmbeddingRunResponse(galleryId = galleryId, targets = targets)
    }
}
