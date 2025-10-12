package voyana.mcpprototype.controller.v2

import org.springframework.web.bind.annotation.*
import voyana.mcpprototype.service.v2.TravelItinerary
import voyana.mcpprototype.service.v2.TravelPlanRequest
import voyana.mcpprototype.service.v2.TravelPlanService

@RestController
@RequestMapping("/api/travel")
class TravelPlanController(
    private val travelPlanService: TravelPlanService
) {

    @PostMapping("/itinerary")
    suspend fun createItinerary(@RequestBody request: TravelPlanRequest): TravelItinerary {
        return travelPlanService.createItinerary(request)
    }

    @GetMapping("/test-itinerary")
    suspend fun testItinerary(): TravelItinerary {
        val request = TravelPlanRequest(
            latitude = 37.5636,
            longitude = 126.9828,
            radius = 3000,
            minRating = 3.6,
            minReviews = 100,
            startDate = "2025-10-20",
            duration = 2
        )
        return travelPlanService.createItinerary(request)
    }
}
