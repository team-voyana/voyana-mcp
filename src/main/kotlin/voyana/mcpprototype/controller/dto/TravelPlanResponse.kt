package voyana.mcpprototype.controller.dto

data class TravelPlanResponse(
    val destination: String,
    val duration: Int,
    val totalBudget: Long,
    val itinerary: List<DayPlan>,              // 일정
    val summary: TravelSummary                 // 요약
)