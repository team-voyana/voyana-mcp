package voyana.mcpprototype.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import voyana.mcpprototype.client.mcp.GeminiApiClient
import voyana.mcpprototype.client.mcp.MCPClient
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse
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
    suspend fun generateTravelPlan(request: TravelPlanRequest): TravelPlanResponse {
        logger.info("여행 계획 생성 요청: 목적지=${request.destination}, 기간=${request.duration}일")

        // 입력 검증
        validateTravelRequest(request)

        try {
            // 1. Google Places API로 실제 장소 검색
            val filteredPlaces = placesSearchService.searchFilteredPlaces(request)
            logger.info("Google Places API 검색 결과: ${filteredPlaces.size}개 장소")

            // 2. Gemini용 데이터 변환
            val placesCandidates = filteredPlaces.map { place ->
                PlaceCandidate(
                    name = place.name,
                    lat = place.lat,
                    lng = place.lng,
                    address = place.address,
                    type = mapPlaceTypeToActivity(place.type),
                    rating = place.rating ?: 4.0
                )
            }

            val placesCandidatesJson = objectMapper.writeValueAsString(placesCandidates)

            // 3. 프롬프트 생성
            val prompt = buildTravelPlanPrompt(request, placesCandidatesJson)

            // 4. Gemini API 호출
            val geminiResponse = geminiApiClient.generateTravelPlan(prompt)

            // 5. JSON 파싱
            val travelPlanResponse = objectMapper.readValue(geminiResponse, TravelPlanResponse::class.java)

            logger.info("여행 계획 생성 완료: 총 비용=${travelPlanResponse.totalBudget}원, 활동 수=${travelPlanResponse.summary.totalActivities}개")
            return travelPlanResponse

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

    private fun buildTravelPlanPrompt(request: TravelPlanRequest, placesCandidatesJson: String): String {
        val totalBudget = request.dailyBudget?.let { it * request.duration } ?: (50000L * request.duration)
        val dailyBudget = request.dailyBudget ?: 50000L

        return """
${request.destination} ${request.duration}일 여행 계획을 오직 JSON 한 개로 생성하세요.

입력 candidates:
$placesCandidatesJson

[필수 규칙]
- 하루 5개 활동 고정: 08:00(아침), 10:00(관광), 12:00(점심), 15:00(카페), 18:00(저녁)
- 타입 매핑(반드시 준수): 08:00/12:00/18:00=RESTAURANT, 10:00=ATTRACTION, 15:00=RESTAURANT
- 활동의 name/lat/lng/address/type/rating은 입력 candidates의 **동일 후보**에서만 복사(임의 작성 금지)
- rating < 3.5 제외, 같은 candidate는 하루 1회만 사용
- 총 예산: ${totalBudget}원 (일일 ${dailyBudget}원), 거리/동선 고려

[금지]
- 주석/설명/문자열 밖 쉼표/생략부호(...) 금지
- 배열/객체 끝 trailing comma 금지
- 스키마 외 키 추가 금지

[출력 스키마(키 고정)]
{
  "destination": "${request.destination}",
  "duration": ${request.duration},
  "totalBudget": $totalBudget,
  "itinerary": [
    {
      "day": 1,
      "date": null,
      "activities": [
        {
          "time": "08:00",
          "type": "RESTAURANT",
          "name": "...",
          "description": "아침식사",
          "location": { "lat": 0, "lng": 0, "address": "..." },
          "duration": 60,
          "cost": 0,
          "rating": 0
        }
      ],
      "dailyCost": 0
    }
  ],
  "summary": {
    "totalCost": 0,
    "totalActivities": ${request.duration * 5},
    "typeCount": { "RESTAURANT": 0, "ATTRACTION": 0, "CAFE": 0 },
    "averageRating": 0
  }
}

오직 위 스키마대로 day 1~${request.duration} 까지 완전한 JSON **한 개**만 출력.
        """.trimIndent()
    }

    private fun validateTravelRequest(request: TravelPlanRequest) {
        // 기존 검증 로직
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