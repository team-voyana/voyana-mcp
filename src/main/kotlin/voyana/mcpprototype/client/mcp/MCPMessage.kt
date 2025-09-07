package voyana.mcpprototype.client.mcp

import kotlinx.serialization.Serializable

@Serializable
data class MCPRequest(
    val method: String,
    val params: MCPParams,
    val id: String
)

@Serializable
data class MCPParams(
    val name: String,
    val arguments: Map<String, Any>
)

@Serializable
data class MCPResponse(
    val id: String,
    val result: MCPResult? = null,
    val error: MCPError? = null
)

@Serializable
data class MCPResult(
    val content: List<MCPContent>,
    val isError: Boolean = false
)

@Serializable
data class MCPContent(
    val type: String,
    val text: String
)

@Serializable
data class MCPError(
    val code: Int,
    val message: String,
    val data: String? = null
)

// Travel specific request/response DTOs
data class MCPTravelRequest(
    val destination: String,
    val duration: Int,
    val dailyBudget: Long?,
    val intensity: String,
    val preferences: List<String>
) {
    fun toMCPArguments(): Map<String, Any> {
        return mapOf(
            "destination" to destination,
            "duration" to duration,
            "daily_budget" to (dailyBudget ?: 100000),
            "intensity" to intensity,
            "preferences" to preferences.joinToString(",")
        )
    }
}

data class MCPTravelResponse(
    val destination: String,
    val itinerary: List<MCPDayPlan>,
    val totalCost: Long,
    val summary: String
)

data class MCPDayPlan(
    val day: Int,
    val activities: List<MCPActivity>
)

data class MCPActivity(
    val time: String,
    val name: String,
    val type: String,
    val description: String? = null,  // nullable로 변경
    val brief: String? = null,        // brief 필드 추가 (fallback용)
    val location: MCPLocation? = null, // nullable로 변경
    val duration: Int? = null,        // nullable로 변경
    val cost: Long? = null,           // nullable로 변경
    val rating: Double? = null        // nullable로 변경
)

data class MCPLocation(
    val lat: Double,
    val lng: Double,
    val address: String?
)