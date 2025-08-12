package voyana.mcpprototype.controller.dto

data class PlaceInfo(
    val placeId: String,
    val name: String,
    val types: List<String>,                  // ["tourist_attraction", "establishment"]
    val rating: Double? = null,
    val address: String? = null,
    val location: Location,
    val photoReference: String? = null,
    val priceLevel: Int? = null              // 0-4 (Google Places 가격 수준)
)

// Google Places API 원본 응답
data class GooglePlacesResponse(
    val results: List<PlaceResult>,
    val status: String
)

data class PlaceResult(
    val place_id: String,
    val name: String,
    val types: List<String>,
    val rating: Double? = null,
    val formatted_address: String? = null,
    val geometry: Geometry,
    val photos: List<Photo>? = null,
    val price_level: Int? = null
)

data class Geometry(
    val location: LatLng
)

data class LatLng(
    val lat: Double,
    val lng: Double
)

data class Photo(
    val photo_reference: String
)