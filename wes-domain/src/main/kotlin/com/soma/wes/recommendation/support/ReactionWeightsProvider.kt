package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.ReactionWeightsDto
import org.springframework.stereotype.Component

/**
 * 갤러리 → 반응 계수. 지금은 모든 갤러리에 같은 기본값을 준다.
 *
 * 계수를 처음부터 표에 두지 않는 이유: 쓰는 쪽이 없는 표가 된다 — 공용 선호 가중치(V14 `preference_models`)가 쓰는 Lambda 없이
 * 배포됐다가 V33에서 지운 이력이 있다. 갤러리·사용자별 반응으로 계수를 고치는 단계가 생기면 이 자리가 그 결과를 읽고,
 * 계산 코드([RecommendationReaction])는 그대로 둔다(#391 ADR 0002).
 */
@Component
class ReactionWeightsProvider {

    fun weightsOf(@Suppress("UNUSED_PARAMETER") galleryId: Long): ReactionWeightsDto = ReactionWeightsDto.DEFAULT
}
