package voyana.mcpprototype.controller.dto

data class DayPlan(
    val day: Int,                              // 1일차, 2일차...
    val date: String? = null,                  // "2025-08-15" (선택사항)
    val activities: List<Activity>,            // 활동 목록
    val dailyCost: Long                        // 해당 일의 총 비용
)