package voyana.mcpprototype.service.places

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.request.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.*

@Service
class PlacesSearchService(
    @Value("\${google.places.api-key:}")
    private val googlePlacesApiKey: String
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 메인 검색 함수 - 요구사항에 맞는 장소들을 찾아서 반환
     */
    fun searchFilteredPlaces(request: TravelPlanRequest): List<DataLocation> {
        logger.info("장소 검색 시작: ${request.destination}")
        
        val center = getSearchCenter(request)
        val radius = request.radiusMeters ?: 2000
        val types = request.includeTypes ?: listOf("restaurant", "tourist_attraction", "cafe")
        
        val allPlaces = mutableListOf<FilteredPlace>()
        // 각 타입별로 검색
        val places = types.map { placeType ->
            searchByType(
                center = center,
                radius = radius,
                placeType = placeType,
                //request = request
            )
        }.map {
            it.places
        }.flatten()

//        logger.info("최종 검색 결과: ${sorted.size}개 (필터링 전: ${allPlaces.size}개)")
        return places // 최대 50개로 제한
    }

    /**
     * 검색 중심점 결정
     */
    private fun getSearchCenter(request: TravelPlanRequest): Pair<Double, Double> {
        return if (request.centerLat != null && request.centerLng != null) {
            request.centerLat to request.centerLng
        } else {
            // destination 기반으로 기본 좌표 반환
            getDefaultCoordinates(request.destination)
        }
    }
    
    /**
     * 목적지 기반 기본 좌표
     */
    private fun getDefaultCoordinates(destination: String): Pair<Double, Double> {
        return when (destination.lowercase().replace(" ", "")) {
            "서울", "seoul" -> 37.5665 to 126.9780
            "부산", "busan" -> 35.1796 to 129.0756
            "제주", "제주도", "jeju" -> 33.4996 to 126.5312
            "인천", "incheon" -> 37.4563 to 126.7052
            "대구", "daegu" -> 35.8714 to 128.6014
            "대전", "daejeon" -> 36.3504 to 127.3845
            "광주", "gwangju" -> 35.1595 to 126.8526
            else -> 37.5665 to 126.9780 // 기본값: 서울
        }
    }

    /**
     * 타입별 장소 검색
     */
    private fun searchByType(
        center: Pair<Double, Double>,
        radius: Int,
        placeType: String,
//        request: TravelPlanRequest
    ): PlaceData {

        val request = RequestBody(
            includedTypes = listOf("restaurant"),
            maxResultCount = 10,
            locationRestriction = LocationRestriction(
                circle = Circle(
                    center = Location(
                        latitude = 37.5665,
                        longitude = 126.9780
                    ),
                    radius = 5000.0
                )
            ),
            languageCode = "ko-kr"
        )

        val objectMapper = ObjectMapper()
        objectMapper.registerModule(
            KotlinModule.Builder()
            .withReflectionCacheSize(512)
            .configure(KotlinFeature.NullToEmptyCollection, false)
            .configure(KotlinFeature.NullToEmptyMap, false)
            .configure(KotlinFeature.NullIsSameAsDefault, false)
            .configure(KotlinFeature.SingletonSupport, false)
            .configure(KotlinFeature.StrictNullChecks, false)
            .build())
        val requestBody = objectMapper.writeValueAsString(request).toRequestBody("application/json".toMediaTypeOrNull())

        val requestBuilder = Request.Builder().url("https://places.googleapis.com/v1/places:searchNearby")
            .header("X-Goog-Api-Key", "AIzaSyB0FgEOUecjEPiN9UqhFDPy9EgQVzCzLC8")
            .header(name = "X-Goog-FieldMask", "places.displayName,places.location,places.rating,places.userRatingCount,places.editorialSummary,places.shortFormattedAddress,places.types,places.regularOpeningHours")
            .post(requestBody)
        
        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                logger.warn("Places API 호출 실패: ${response.code}")

                throw RuntimeException()
                //return createSamplePlaces(placeType, center)
            }

            objectMapper.readValue<PlaceData>(body)
        }
    }

    /**
     * Places API 응답 파싱
     */
    private fun parsePlacesResponse(
        json: JSONObject, 
        placeType: String, 
        searchCenter: Pair<Double, Double>
    ): List<FilteredPlace> {
        val results = json.optJSONArray("places") ?: return emptyList()
        val places = mutableListOf<FilteredPlace>()

        for (i in 0 until minOf(results.length(), 20)) { // 타입당 최대 20개
            try {
                val place = results.getJSONObject(i)
                val parsedPlace = parsePlace(place, placeType, searchCenter)
                if (parsedPlace != null) {
                    places.add(parsedPlace)
                }
            } catch (e: Exception) {
                logger.debug("장소 파싱 실패: ${e.message}")
            }
        }

        return places
    }

    /**
     * 개별 장소 파싱
     */
    private fun parsePlace(
        place: JSONObject, 
        placeType: String, 
        searchCenter: Pair<Double, Double>
    ): FilteredPlace? {
        try {
            val geometry = place.optJSONObject("geometry") ?: return null
            val location = geometry.optJSONObject("location") ?: return null
            
            val lat = location.optDouble("lat")
            val lng = location.optDouble("lng")
            val name = place.optString("name", "")
            val placeId = place.optString("place_id", "")
            
            if (name.isBlank() || placeId.isBlank()) return null

            val rating = if (place.has("rating")) place.optDouble("rating") else null
            val userRatingsTotal = if (place.has("user_ratings_total")) place.optInt("user_ratings_total") else null
            val priceLevel = if (place.has("price_level")) place.optInt("price_level") else null
            
            // 거리 계산
            val distance = calculateDistance(searchCenter.first, searchCenter.second, lat, lng)
            
            // 영업시간 정보
            val openingHours = place.optJSONObject("opening_hours")
            val isOpenNow = openingHours?.optBoolean("open_now") ?: true
            
            // 타입 정보
            val typesArray = place.optJSONArray("types")
            val types = mutableListOf<String>()
            if (typesArray != null) {
                for (j in 0 until typesArray.length()) {
                    types.add(typesArray.optString(j))
                }
            }

            return FilteredPlace(
                placeId = placeId,
                name = name,
                type = placeType,
                lat = lat,
                lng = lng,
                rating = rating,
                userRatingsTotal = userRatingsTotal,
                priceLevel = priceLevel,
                distance = distance,
                isOpenNow = isOpenNow,
                types = types,
                address = place.optString("vicinity", ""),
                photoReference = extractPhotoReference(place)
            )
        } catch (e: Exception) {
            logger.debug("장소 파싱 오류: ${e.message}")
            return null
        }
    }

    /**
     * 사진 참조 추출
     */
    private fun extractPhotoReference(place: JSONObject): String? {
        val photos = place.optJSONArray("photos")
        return if (photos != null && photos.length() > 0) {
            photos.getJSONObject(0).optString("photo_reference")
        } else null
    }

    /**
     * 품질 필터링 적용
     */
    private fun applyQualityFilters(
        places: List<FilteredPlace>, 
        request: TravelPlanRequest
    ): List<FilteredPlace> {
        return places.filter { place ->
            // 평점 필터
            val minRating = request.minRating ?: 3.8
            val rating = place.rating ?: 0.0
            if (rating > 0 && rating < minRating) return@filter false
            
            // 리뷰 수 필터
            val minUserRatings = request.minUserRatings ?: 100
            val userRatings = place.userRatingsTotal ?: 0
            if (userRatings > 0 && userRatings < minUserRatings) return@filter false
            
            // 가격대 필터
            val allowedPriceLevels = request.priceLevels ?: listOf(0, 1, 2, 3)
            val priceLevel = place.priceLevel
            if (priceLevel != null && priceLevel !in allowedPriceLevels) return@filter false
            
            // 거리 필터
            val maxDistance = request.maxTravelDistance ?: 5000
            if (place.distance > maxDistance) return@filter false
            
            // 영업시간 필터
            if (request.openNow == true && !place.isOpenNow) return@filter false
            
            true
        }
    }

    /**
     * 관련성에 따른 정렬
     */
    private fun sortPlacesByRelevance(
        places: List<FilteredPlace>, 
        request: TravelPlanRequest
    ): List<FilteredPlace> {
        return places.sortedWith(compareBy<FilteredPlace> { place ->
            // 1순위: 거리 (가까울수록 좋음)
            place.distance
        }.thenByDescending { place ->
            // 2순위: 평점 (높을수록 좋음)
            place.rating ?: 0.0
        }.thenByDescending { place ->
            // 3순위: 리뷰 수 (많을수록 좋음)
            place.userRatingsTotal ?: 0
        })
    }

    /**
     * 거리 계산 (하버사인 공식)
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c
    }
}

/**
 * 필터링된 장소 데이터 클래스
 */
data class FilteredPlace(
    val placeId: String,
    val name: String,
    val type: String,                        // 문자열로 수정
    val lat: Double,
    val lng: Double,
    val rating: Double?,
    val userRatingsTotal: Int?,
    val priceLevel: Int?,                    // 0=무료, 1=저렴, 2=보통, 3=비쌈, 4=매우비쌈
    val distance: Double,                    // 검색 중심점으로부터의 거리 (미터)
    val isOpenNow: Boolean,
    val types: List<String>,
    val address: String,
    val photoReference: String?
)

data class LocationRestriction(
    val circle: Circle
)

data class Circle(
    val center: Location,
    val radius: Double
)
data class Location(
    val latitude: Double,
    val longitude: Double
)
data class RequestBody(
    val includedTypes: List<String>,
    val maxResultCount: Int,
    val locationRestriction: LocationRestriction,
    val languageCode: String
)

data class PlaceData(
    val places: List<DataLocation>
)
data class DataLocation(
    val displayName: DisplayData,
    val rating: Double,
    val userRatingCount: Int,
    val location: Radius,
    var reason: String? = null,
    var aiRating: Double? = null,
)
data class DisplayData(
    val text: String,
    val languageCode: String
)
data class Radius(
    val latitude: Double,
    val longitude: Double
)
