package voyana.mcpprototype.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse
import voyana.mcpprototype.service.TravelService
import voyana.mcpprototype.service.TravelServiceException
import jakarta.validation.Valid

@RestController
@RequestMapping("/api/travel")
@Validated
class TravelController(
    private val travelService: TravelService
) {
    private val logger = LoggerFactory.getLogger(TravelController::class.java)

    /**
     * 여행 계획 생성 API
     */
    @PostMapping("/plan")
    fun createTravelPlan(@Valid @RequestBody request: TravelPlanRequest): ResponseEntity<*> {
        logger.info("여행 계획 요청 수신: ${request.destination}, ${request.duration}일")
        
        return try {
            val response = travelService.generateTravelPlan(request)
            ResponseEntity.ok(response)
        } catch (e: TravelServiceException) {
            logger.error("여행 서비스 오류: ${e.message}")
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse("TRAVEL_SERVICE_ERROR", e.message ?: "여행 계획 생성에 실패했습니다"))
        } catch (e: Exception) {
            logger.error("예상치 못한 오류: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다"))
        }
    }

}

/**
 * 에러 응답 DTO
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: String = java.time.LocalDateTime.now().toString()
)