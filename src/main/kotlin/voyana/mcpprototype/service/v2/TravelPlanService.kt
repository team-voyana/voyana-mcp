package voyana.mcpprototype.service.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import voyana.mcpprototype.client.mcp.GeminiApiClient

import java.time.LocalDate
import kotlin.math.*


@Service
class TravelPlanService(
    private val placesService: GooglePlacesService,
    private val geminiClient: GeminiApiClient
) {
    private val mapper = jacksonObjectMapper()

    suspend fun createItinerary(request: TravelPlanRequest): TravelItinerary {
        // 1. Places 수집 & 분류 (Kotlin)
        val categorized = getRecommendedPlaces(request)

        // 2. Gemini가 각 카테고리별 최적 순서 결정
        val optimized = optimizeWithAI(categorized, request)

        // 3. 시간대별 일정 조립 (Kotlin)
        val dailyPlans = createDailyPlans(
            places = optimized,
            duration = request.duration,
            startDate = request.startDate
        )

        val totalDistance = calculateTotalDistance(dailyPlans)

        return TravelItinerary(
            destination = "Seoul",
            startDate = request.startDate,
            endDate = calculateEndDate(request.startDate, request.duration),
            duration = request.duration,
            totalEstimatedDistance = totalDistance,
            dailyPlans = dailyPlans
        )
    }

    private suspend fun getRecommendedPlaces(request: TravelPlanRequest): Map<String, List<Place>> {
        val allPlaces = placesService.searchNearby(
            lat = request.latitude,
            lng = request.longitude,
            radius = request.radius
        ).filter {
            it.rating >= request.minRating &&
                    it.userRatingCount >= request.minReviews
        }

        println("Places API: ${allPlaces.size}개")

        val restaurants = allPlaces
            .filter {
                it.type.contains("restaurant", ignoreCase = true) ||
                        it.type.contains("food", ignoreCase = true) ||
                        it.type.contains("cafe", ignoreCase = true)
            }
            .sortedByDescending { it.rating * it.userRatingCount }
            .take(request.duration * 3)


        val attractions = allPlaces
            .filter {
                it.type.contains("tourist_attraction", ignoreCase = true) ||
                        it.type.contains("museum", ignoreCase = true) ||
                        it.type.contains("landmark", ignoreCase = true) ||
                        it.type.contains("cultural", ignoreCase = true) ||
                        it.type.contains("market", ignoreCase = true) ||
                        it.type.contains("shopping", ignoreCase = true)
            }
            .sortedByDescending { it.rating * it.userRatingCount }
            .take(request.duration * 3)

        val shopping = allPlaces
            .filter {
                it.type.contains("store", ignoreCase = true) ||
                        it.type.contains("shopping", ignoreCase = true) ||
                        it.type.contains("department", ignoreCase = true)
            }
            .sortedByDescending { it.rating * it.userRatingCount }
            .take(request.duration * 2)

        println("식당: ${restaurants.size}, 관광: ${attractions.size}, 쇼핑: ${shopping.size}")

        return mapOf(
            "restaurants" to restaurants,
            "attractions" to attractions,
            "shopping" to shopping
        )
    }

    // Gemini 가 최적 순서 결정
    private suspend fun optimizeWithAI(
        places: Map<String, List<Place>>,
        request: TravelPlanRequest
    ): Map<String, List<RecommendedPlace>> {

        val restaurants = places["restaurants"] ?: emptyList()
        val attractions = places["attractions"] ?: emptyList()
        val shopping = places["shopping"] ?: emptyList()

        if (restaurants.isEmpty() && attractions.isEmpty() && shopping.isEmpty()) {
            return mapOf(
                "restaurants" to emptyList(),
                "attractions" to emptyList(),
                "shopping" to emptyList()
            )
        }

        val prompt = buildOptimizationPrompt(
            restaurants = restaurants,
            attractions = attractions,
            shopping = shopping,
            centerLat = request.latitude,
            centerLng = request.longitude,
            duration = request.duration
        )

        return try {
            val jsonResponse = geminiClient.generateTravelPlan(prompt)
            parseOptimizedResponse(jsonResponse)
        } catch (e: Exception) {
            println("AI 최적화 실패, 원본 순서 사용: ${e.message}")
            // Fallback: 원본 순서 그대로
            mapOf(
                "restaurants" to restaurants.map { toRecommendedPlace(it) },
                "attractions" to attractions.map { toRecommendedPlace(it) },
                "shopping" to shopping.map { toRecommendedPlace(it) }
            )
        }
    }

    private fun buildOptimizationPrompt(
        restaurants: List<Place>,
        attractions: List<Place>,
        shopping: List<Place>,
        centerLat: Double,
        centerLng: Double,
        duration: Int
    ): String {
        val data = mapOf(
            "center" to mapOf("lat" to centerLat, "lng" to centerLng),
            "duration" to duration,
            "restaurants" to restaurants.map { placeToMap(it) },
            "attractions" to attractions.map { placeToMap(it) },
            "shopping" to shopping.map { placeToMap(it) }
        )

        return """Optimize travel route for ${duration} days. Consider proximity and ratings.
        
${mapper.writeValueAsString(data)}

Task:
1. Sort each category by optimal visit order (minimize travel distance)
2. Add brief reason for each place

Output JSON:
{
  "restaurants": [{"name":"","rating":0,"userRatingCount":0,"address":"","type":"","latitude":0,"longitude":0,"openNow":true,"reason":""}],
  "attractions": [...],
  "shopping": [...]
}

Rules: Keep all place data. Optimize order within each category."""
    }

    private fun placeToMap(place: Place) = mapOf(
        "name" to place.name,
        "rating" to place.rating,
        "userRatingCount" to place.userRatingCount,
        "address" to place.address,
        "type" to place.type,
        "latitude" to place.latitude,
        "longitude" to place.longitude,
        "openNow" to place.openNow
    )

    private fun parseOptimizedResponse(json: String): Map<String, List<RecommendedPlace>> {
        val cleanJson = json.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val response = mapper.readValue<OptimizedResponse>(cleanJson)

        println("Gemini 최적화: 식당 ${response.restaurants.size}, 관광 ${response.attractions.size}, 쇼핑 ${response.shopping.size}")

        return mapOf(
            "restaurants" to response.restaurants,
            "attractions" to response.attractions,
            "shopping" to response.shopping
        )
    }

    private fun toRecommendedPlace(place: Place) = RecommendedPlace(
        name = place.name,
        rating = place.rating,
        userRatingCount = place.userRatingCount,
        address = place.address,
        type = place.type,
        latitude = place.latitude,
        longitude = place.longitude,
        openNow = place.openNow,
        reason = "Highly rated with ${place.userRatingCount} reviews"
    )

    private fun createDailyPlans(
        places: Map<String, List<RecommendedPlace>>,
        duration: Int,
        startDate: String?
    ): List<DailyPlan> {
        val restaurants = places["restaurants"] ?: emptyList()
        val attractions = places["attractions"] ?: emptyList()
        val shopping = places["shopping"] ?: emptyList()

        val usedPlaces = mutableSetOf<String>()

        // ✅ 일반 함수로 변경
        fun getNextPlace(placeList: List<RecommendedPlace>): RecommendedPlace? {
            for (place in placeList) {
                if (!usedPlaces.contains(place.name)) {
                    usedPlaces.add(place.name)
                    return place
                }
            }
            return null
        }

        return (1..duration).map { day ->
            val dayPlaces = mutableListOf<PlaceSchedule>()
            var order = 1

            // 오전: 관광
            val morningAttraction = getNextPlace(attractions)
            if (morningAttraction != null) {
                dayPlaces.add(PlaceSchedule(
                    order = order++,
                    timeSlot = "09:00-11:30",
                    place = morningAttraction,
                    distanceFromPrevious = null
                ))
            }

            // 점심: 식당
            val lunchRestaurant = getNextPlace(restaurants)
            if (lunchRestaurant != null) {
                dayPlaces.add(PlaceSchedule(
                    order = order++,
                    timeSlot = "12:00-13:30",
                    place = lunchRestaurant,
                    distanceFromPrevious = calculateDistance(
                        dayPlaces.lastOrNull()?.place,
                        lunchRestaurant
                    )
                ))
            }

            // 오후: 쇼핑
            val afternoonShopping = getNextPlace(shopping)
            if (afternoonShopping != null) {
                dayPlaces.add(PlaceSchedule(
                    order = order++,
                    timeSlot = "14:00-16:00",
                    place = afternoonShopping,
                    distanceFromPrevious = calculateDistance(
                        dayPlaces.lastOrNull()?.place,
                        afternoonShopping
                    )
                ))
            }

            // 오후: 관광
            val afternoonAttraction = getNextPlace(attractions)
            if (afternoonAttraction != null) {
                dayPlaces.add(PlaceSchedule(
                    order = order++,
                    timeSlot = "16:30-18:00",
                    place = afternoonAttraction,
                    distanceFromPrevious = calculateDistance(
                        dayPlaces.lastOrNull()?.place,
                        afternoonAttraction
                    )
                ))
            }

            // 저녁: 식당
            val dinnerRestaurant = getNextPlace(restaurants)
            if (dinnerRestaurant != null) {
                dayPlaces.add(PlaceSchedule(
                    order = order++,
                    timeSlot = "18:30-20:00",
                    place = dinnerRestaurant,
                    distanceFromPrevious = calculateDistance(
                        dayPlaces.lastOrNull()?.place,
                        dinnerRestaurant
                    )
                ))
            }

            DailyPlan(
                day = day,
                date = calculateDate(startDate, day),
                places = dayPlaces
            )
        }
    }

    private fun calculateDistance(from: RecommendedPlace?, to: RecommendedPlace): Double? {
        if (from == null) return null

        val R = 6371.0
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun calculateTotalDistance(dailyPlans: List<DailyPlan>): Double {
        return dailyPlans.sumOf { plan ->
            plan.places.mapNotNull { it.distanceFromPrevious }.sum()
        }
    }

    private fun calculateDate(startDate: String?, dayNumber: Int): String? {
        if (startDate == null) return null
        return try {
            val date = LocalDate.parse(startDate)
            date.plusDays((dayNumber - 1).toLong()).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateEndDate(startDate: String?, duration: Int): String? {
        if (startDate == null) return null
        return try {
            val date = LocalDate.parse(startDate)
            date.plusDays((duration - 1).toLong()).toString()
        } catch (e: Exception) {
            null
        }
    }
}

// ✅ Gemini 응답 파싱용 data class 추가
data class OptimizedResponse(
    val restaurants: List<RecommendedPlace>,
    val attractions: List<RecommendedPlace>,
    val shopping: List<RecommendedPlace>
)

data class TravelPlanRequest(
    val latitude: Double,
    val longitude: Double,
    val radius: Int = 3000,
    val minRating: Double = 3.8,
    val minReviews: Int = 300,
    val startDate: String?,
    val duration: Int
)

data class RecommendedPlace(
    val name: String,
    val rating: Double,
    val userRatingCount: Int,
    val address: String,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val openNow: Boolean?,
    val reason: String
)

data class TravelItinerary(
    val destination: String,
    val startDate: String?,
    val endDate: String?,
    val duration: Int,
    val totalEstimatedDistance: Double?,
    val dailyPlans: List<DailyPlan>
)

data class DailyPlan(
    val day: Int,
    val date: String?,
    val places: List<PlaceSchedule>
)

data class PlaceSchedule(
    val order: Int,
    val timeSlot: String,
    val place: RecommendedPlace,
    val distanceFromPrevious: Double?
)