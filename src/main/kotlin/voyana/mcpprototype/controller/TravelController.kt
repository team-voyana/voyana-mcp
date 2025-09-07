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
}