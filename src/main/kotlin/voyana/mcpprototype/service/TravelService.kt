package voyana.mcpprototype.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import voyana.mcpprototype.client.mcp.GeminiApiClient
import voyana.mcpprototype.client.mcp.MCPClient
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse
import voyana.mcpprototype.service.places.DataLocation
import voyana.mcpprototype.service.places.PlacesSearchService


@Service
class TravelService(
    private val geminiApiClient: GeminiApiClient,
    private val placesSearchService: PlacesSearchService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(TravelService::class.java)

    /**
     * 여행 계획 생성 - Google Places API + Gemini API
     */
    suspend fun generateTravelPlan(request: TravelPlanRequest): String {
        logger.info("여행 계획 생성 요청: 목적지=${request.destination}, 기간=${request.duration}일")


        try {
            // 1. Google Places API로 실제 장소 검색
            val filteredPlaces = placesSearchService.searchFilteredPlaces(request)
            logger.info("Google Places API 검색 결과: ${filteredPlaces.size}개 장소")

            // 2. Gemini용 데이터 변환
            // 3. 프롬프트 생성
            val prompt = buildTravelPlanPrompt(request, filteredPlaces)

            // 4. Gemini API 호출
            return geminiApiClient.generateTravelPlan(prompt)

        } catch (e: Exception) {
            logger.error("여행 계획 생성 중 오류 발생: ${e.message}", e)
            throw RuntimeException("여행 계획 생성에 실패했습니다: ${e.message}", e)
        }
    }

    private fun mapPlaceTypeToActivity(placeType: String): String {
        return when (placeType.lowercase()) {
            "restaurant", "food" -> "RESTAURANT"
            "cafe" -> "CAFE"
            "tourist_attraction", "attraction" -> "ATTRACTION"
            else -> "ATTRACTION"
        }
    }

    private fun buildTravelPlanPrompt(request: TravelPlanRequest, filteredPlaces: List<DataLocation>): String {
        val totalBudget = request.dailyBudget?.let { it * request.duration } ?: (50000L * request.duration)
        val dailyBudget = request.dailyBudget ?: 50000L

        return """
${request.destination} ${request.duration}일 여행 계획을 오직 JSON 한 개로 생성하세요.

입력 candidates:
${filteredPlaces}

[필수 규칙]
- 입력 받은 데이터를 바탕으로 구성
- 활동의 name/lat/lng/address/type/rating은 입력 candidates의 **동일 후보**에서만 복사(임의 작성 금지)
- rating < 3.5 제외, 같은 candidate는 하루 1회만 사용
- 총 예산: ${totalBudget}원 (일일 ${dailyBudget}원), 거리/동선 고려
- reason에 추천 이유, aiRating에 추천 점수를 기록할 것 - aiRating에는 최소 0점, 최대 5점으로 자체 평가

[금지]
- 주석/설명/문자열 밖 쉼표/생략부호(...) 금지
- 배열/객체 끝 trailing comma 금지
- 스키마 외 키 추가 금지

[결과]
- 입력받은 filteredPlaces 항목을 정렬해 줘 (앞에 있을 수록 추천하기 좋은 항목)
- reason과 aiRating이 추가되는 것 외에는 변환 없이 모델 그대로를 리스트로 받고 싶어 (List<DataLocation 형태로)

오직 위 스키마대로 day 1~${request.duration} 까지 완전한 JSON **한 개**만 출력.
        """.trimIndent()
    }
}

// 데이터 클래스 추가
data class PlaceCandidate(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val type: String,
    val rating: Double
)