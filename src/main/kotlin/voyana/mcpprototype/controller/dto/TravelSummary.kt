package voyana.mcpprototype.controller.dto

data class TravelSummary(
    val totalCost: Long,                      // 총 비용
    val totalActivities: Int,                 // 총 활동 개수
    val typeCount: Map<ActivityType, Int>,    // 활동 유형별 개수
    val averageRating: Double? = null         // 평균 평점
)