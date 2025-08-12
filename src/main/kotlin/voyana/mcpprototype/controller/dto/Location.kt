package voyana.mcpprototype.controller.dto

data class Location(
    val lat: Double,                          // 위도
    val lng: Double,                          // 경도
    val address: String? = null               // 주소 (선택사항)
)