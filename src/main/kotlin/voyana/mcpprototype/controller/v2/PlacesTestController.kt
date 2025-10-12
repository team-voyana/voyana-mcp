package voyana.mcpprototype.controller.v2

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import voyana.mcpprototype.service.v2.GooglePlacesService
import voyana.mcpprototype.service.v2.Place

@RestController
@RequestMapping("/api/places")
class PlacesTestController(
    private val placesService: GooglePlacesService
) {

    @GetMapping("/nearby")
    suspend fun searchNearby(
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(defaultValue = "3000") radius: Int
    ): List<Place> {
        return placesService.searchNearby(lat, lng, radius)
    }

    // 명동 주변 맛집 검색 테스트
    @GetMapping("/test-myeongdong")
    suspend fun testMyeongdong(): List<Place> {
        // 명동역 좌표
        return placesService.searchNearby(
            lat = 37.5636,
            lng = 126.9828,
            radius = 2000,
            types = listOf("restaurant")
        )
    }
}