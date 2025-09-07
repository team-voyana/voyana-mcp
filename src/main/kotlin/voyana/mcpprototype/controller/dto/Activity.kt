package voyana.mcpprototype.controller.dto

data class Activity(
    val time: String,                          // "09:00"
    val type: ActivityType,                    // enum으로 관리
    val name: String,                          // "경복궁"
    val description: String,                   // "조선시대 궁궐"
    val location: Location,                    // 위치 정보
    val duration: Int,                         // 소요시간(분)
    val cost: Long,                           // 비용
    val rating: Double? = null                 // 평점 (Google Places 기준)
)

enum class ActivityType {
    ATTRACTION,     // 관광지
    RESTAURANT,     // 식당
    SHOPPING,       // 쇼핑
    TRANSPORT,      // 이동
    CAFE,
    BREAK          // 휴식
}