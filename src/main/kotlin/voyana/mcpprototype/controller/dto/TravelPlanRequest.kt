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

    // === 새로 추가된 위치 및 범위 설정 ===
    val centerLat: Double? = null,              // 중심 위도
    val centerLng: Double? = null,              // 중심 경도  
    val radiusMeters: Int? = 2000,              // 검색 반경 (미터)
    
    // === 장소 타입 필터링 (문자열로 수정) ===
    val includeTypes: List<String>? = listOf(
        "restaurant",
        "tourist_attraction", 
        "cafe"
    ),
    
    // === 품질 필터링 ===
    val minRating: Double? = 3.8,               // 최소 평점
    val minUserRatings: Int? = 100,             // 최소 리뷰 수
    val priceLevels: List<Int>? = listOf(0,1,2,3), // 가격대 (0=무료, 1=저렴, 2=보통, 3=비쌈, 4=매우비쌈)
    
    // === 시간 관련 설정 ===
    val openNow: Boolean? = null,               // 현재 영업중인 곳만
    val considerHolidays: Boolean? = true,      // 휴일 고려
    val avoidBreakTime: Boolean? = true,        // 브레이크타임 회피
    
    // === 식사 및 동선 설정 ===
    val mealsPerDay: Int? = 3,                  // 하루 식사 횟수 (2~3끼)
    val optimizeRoute: Boolean? = true,         // 동선 최적화
    val maxTravelDistance: Int? = 5000,         // 최대 이동거리 (미터)
    
    // === 여행 스타일 (문자열로 수정) ===
    val preferences: List<String> = emptyList(), // ["food", "culture", "nature"]
    val travelStyle: String? = "BALANCED"       // "RELAXED", "BALANCED", "INTENSIVE", "CULTURAL", "FOODIE", "NATURE"
)