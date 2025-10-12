package voyana.mcpprototype.service.v2

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.LocalDateTime

@Service
class GooglePlacesService(
    @Value("\${google.places.api-key}") private val placesApiKey: String  // Places 전용 키
) {
    private val webClient = WebClient.builder()
        .baseUrl("https://places.googleapis.com")
        .build()

    suspend fun searchNearby(
        lat: Double,
        lng: Double,
        radius: Int = 3000,
        types: List<String>? = null,
        maxPages: Int = 3
    ): List<Place> {
        val allPlaces = mutableListOf<Place>()
        var pageToken: String? = null
        var pageCount = 0

        while (pageCount < maxPages) {
            val requestBody = buildSearchRequest(lat, lng, radius, types, pageToken)

            val response = webClient.post()
                .uri("/v1/places:searchNearby")
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", placesApiKey)
                .header("X-Goog-FieldMask", getFieldMask())
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<PlacesResponse>()

            val places = response.places.mapNotNull { convertToPlace(it) }
            allPlaces.addAll(places)

            // ✅ 다음 페이지 토큰 확인
            pageToken = response.nextPageToken

            if (pageToken == null) {
                println("   ℹ️ 더 이상 결과 없음")
                break
            }

            pageCount++

            // ✅ 페이징 사이에 잠깐 대기 (API 제한 회피)
            if (pageToken != null) {
                kotlinx.coroutines.delay(200)
            }
        }

        return allPlaces
    }

    private fun buildSearchRequest(
        lat: Double,
        lng: Double,
        radius: Int,
        types: List<String>?,
        pageToken: String?

    ): Map<String, Any> {
        val request = mutableMapOf<String, Any>(
            "locationRestriction" to mapOf(
                "circle" to mapOf(
                    "center" to mapOf(
                        "latitude" to lat,
                        "longitude" to lng
                    ),
                    "radius" to radius.toDouble()
                )
            ),
            "maxResultCount" to 20
        )

        if (!types.isNullOrEmpty()) {
            request["includedTypes"] = types
        }

        if (pageToken != null) {
            request["pageToken"] = pageToken
        }

        return request
    }

    private fun getFieldMask(): String {
        return listOf(
            "places.displayName",
            "places.formattedAddress",
            "places.location",
            "places.rating",
            "places.userRatingCount",
            "places.types",
            "places.regularOpeningHours"
        ).joinToString(",")
    }

    private fun convertToPlace(googlePlace: GooglePlace): Place? {
        val rating = googlePlace.rating ?: return null
        val userRatingCount = googlePlace.userRatingCount ?: return null
        val address = googlePlace.formattedAddress ?: return null

        val type = googlePlace.types.firstOrNull() ?: "unknown"

        return Place(
            name = googlePlace.displayName.text,
            rating = rating,
            userRatingCount = userRatingCount,
            address = address,
            type = type,
            latitude = googlePlace.location.latitude,
            longitude = googlePlace.location.longitude,
            openNow = googlePlace.regularOpeningHours?.openNow
        )
    }
}

// Google Places API 응답 모델
data class PlacesResponse(
    val places: List<GooglePlace>,
    val nextPageToken: String?
)

data class GooglePlace(
    val displayName: DisplayName,
    val location: Location,
    val rating: Double?,
    val userRatingCount: Int?,
    val types: List<String>,
    val formattedAddress: String?,
    val regularOpeningHours: RegularOpeningHours?
)

data class DisplayName(
    val text: String,
    val languageCode: String?
)

data class Location(
    val latitude: Double,
    val longitude: Double
)

data class RegularOpeningHours(
    val openNow: Boolean?,
    val weekdayDescriptions: List<String>?
)

// 우리 서비스에서 사용할 간소화된 모델
data class Place(
    val name: String,
    val rating: Double,
    val userRatingCount: Int,
    val address: String,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val openNow: Boolean?
)