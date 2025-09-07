package voyana.mcpprototype.controller

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse
import voyana.mcpprototype.service.TravelService
import voyana.mcpprototype.service.places.PlacesSearchService

@RestController
@RequestMapping("/api/travel")
class TravelController(
    private val travelService: TravelService,
    private val placesSearchService: PlacesSearchService
) {
    private val logger = LoggerFactory.getLogger(TravelController::class.java)

    @PostMapping("/plan")
    suspend fun createTravelPlan(@RequestBody request: TravelPlanRequest): ResponseEntity<*> {
        return try {
            logger.info("여행 계획 요청 수신: ${request.destination}, ${request.duration}일")
            
            val travelPlan = travelService.generateTravelPlan(request)
            ResponseEntity.ok(travelPlan)
        } catch (e: Exception) {
            logger.error("여행 서비스 오류: ${e.message}", e)
            ResponseEntity.badRequest().body(
                mapOf(
                    "error" to "여행 계획 생성에 실패했습니다",
                    "message" to e.message
                )
            )
        }
    }
    
    // === 간단한 테스트 엔드포인트 ===
    @GetMapping("/test")
    fun testEndpoint(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "message" to "새로운 기능이 준비되었습니다",
            "timestamp" to System.currentTimeMillis(),
            "supportedTypes" to listOf("restaurant", "tourist_attraction", "cafe", "museum", "park")
        ))
    }
    
    // === 장소 검색 테스트 API ===
    @PostMapping("/places/search")
    fun searchPlaces(@RequestBody request: TravelPlanRequest): ResponseEntity<*> {
        return try {
            logger.info("장소 검색 요청: ${request.destination}")
            
            val places = placesSearchService.searchFilteredPlaces(request)
            
            ResponseEntity.ok(mapOf(
                "destination" to request.destination,
                "totalFound" to places.size,
                "searchCenter" to mapOf(
                    "lat" to (request.centerLat ?: 37.5665),
                    "lng" to (request.centerLng ?: 126.9780)
                ),
                "radius" to (request.radiusMeters ?: 2000),
                "filters" to mapOf(
                    "minRating" to (request.minRating ?: 3.8),
                    "minUserRatings" to (request.minUserRatings ?: 100),
                    "placeTypes" to (request.includeTypes ?: listOf("restaurant", "tourist_attraction", "cafe"))
                ),
                "places" to places.map { place ->
                    mapOf(
                        "name" to place.name,
                        "type" to place.type,
                        "rating" to place.rating,
                        "userRatingsTotal" to place.userRatingsTotal,
                        "priceLevel" to place.priceLevel,
                        "distance" to String.format("%.0f미터", place.distance),
                        "location" to mapOf(
                            "lat" to place.lat,
                            "lng" to place.lng
                        ),
                        "address" to place.address,
                        "isOpenNow" to place.isOpenNow
                    )
                }
            ))
        } catch (e: Exception) {
            logger.error("장소 검색 오류: ${e.message}", e)
            ResponseEntity.badRequest().body(
                mapOf(
                    "error" to "장소 검색에 실패했습니다",
                    "message" to e.message
                )
            )
        }
    }
}