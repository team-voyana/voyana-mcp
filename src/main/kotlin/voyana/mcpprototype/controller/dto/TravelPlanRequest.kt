package voyana.mcpprototype.controller.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class TravelPlanRequest(
    @field:NotBlank
    val destination: String,                    // "서울"

    @field:NotNull
    @field:Min(1) @field:Max(10)
    val duration: Int,                          // 일수 (1~10일)

    @field:Min(10000)
    val dailyBudget: Long?,                     // 일일 예산 (원, nullable)

    @field:NotBlank
    val intensity: String,                      // "low", "medium", "high"

    val preferences: List<String> = emptyList() // ["food", "culture", "nature"]
)