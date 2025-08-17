package voyana.mcpprototype.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import voyana.mcpprototype.client.mcp.MCPClient
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse

@Service
class TravelService(
    private val mcpClient: MCPClient
) {
    private val logger = LoggerFactory.getLogger(TravelService::class.java)

    /**
     * 여행 계획 생성
     */
    fun generateTravelPlan(request: TravelPlanRequest): TravelPlanResponse {
        logger.info("여행 계획 생성 요청: 목적지=${request.destination}, 기간=${request.duration}일")
        
        // 입력 검증
        validateTravelRequest(request)
        
        try {
            // MCP Client를 통해 여행 계획 생성
            val response = mcpClient.generateTravelPlan(request)
            
            logger.info("여행 계획 생성 완료: 총 비용=${response.totalBudget}원, 활동 수=${response.summary.totalActivities}개")
            return response
            
        } catch (e: Exception) {
            logger.error("여행 계획 생성 중 오류 발생: ${e.message}", e)
            throw TravelServiceException("여행 계획 생성에 실패했습니다: ${e.message}", e)
        }
    }

    /**
     * 여행 요청 검증
     */
    private fun validateTravelRequest(request: TravelPlanRequest) {
        require(request.destination.isNotBlank()) { "목적지는 필수입니다" }
        require(request.duration in 1..10) { "여행 기간은 1일에서 10일 사이여야 합니다" }
        require(request.dailyBudget == null || request.dailyBudget >= 10000) { "일일 예산은 최소 10,000원 이상이어야 합니다" }
        require(request.intensity in listOf("low", "medium", "high")) { "강도는 low, medium, high 중 하나여야 합니다" }
    }
}

/**
 * 여행 서비스 예외
 */
class TravelServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)