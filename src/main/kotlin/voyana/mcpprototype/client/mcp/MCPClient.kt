package voyana.mcpprototype.client.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import voyana.mcpprototype.controller.dto.*
import java.io.IOException
import java.util.*

@Component
class MCPClient(
    @Value("\${mcp.server.url:http://localhost:8080}")
    private val mcpServerUrl: String
) {
    private val logger = LoggerFactory.getLogger(MCPClient::class.java)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(180000, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180000, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * MCP Server에 여행 계획 생성 요청
     */
    fun generateTravelPlan(request: TravelPlanRequest): TravelPlanResponse {
        logger.info("MCP Client: 여행 계획 생성 요청 시작 - 목적지: ${request.destination}")

            // 1. TravelPlanRequest를 MCP 형식으로 변환
            val mcpTravelRequest = MCPTravelRequest(
                destination = request.destination,
                duration = request.duration,
                dailyBudget = request.dailyBudget,
                intensity = request.intensity,
                preferences = request.preferences
            )

            // 2. MCP 메시지 생성
            val mcpRequest = MCPRequest(
                method = "tools/call",
                params = MCPParams(
                    name = "travel_planner",
                    arguments = mcpTravelRequest.toMCPArguments()
                ),
                id = UUID.randomUUID().toString()
            )

            // 3. MCP Server 호출
            val mcpResponse = callMCPServer(mcpRequest)
            
            // 4. 응답을 TravelPlanResponse로 변환
            return convertToTravelPlanResponse(mcpResponse, request)
            

    }

    /**
     * MCP Server 실제 호출
     */
    private fun callMCPServer(mcpRequest: MCPRequest): MCPResponse {
        val jsonBody = objectMapper.writeValueAsString(mcpRequest)
        logger.debug("MCP 요청 JSON: $jsonBody")

        val requestBody = jsonBody.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val httpRequest = Request.Builder()
            .url("$mcpServerUrl/mcp")
            .post(requestBody)
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->

            logger.info("response: ${response}")

            if (!response.isSuccessful) {
                logger.error("MCP Server 호출 실패: ${response.code} - ${response.message}")
                throw IOException("MCP Server 호출 실패: ${response.code}")
            }

            // 전체 응답을 안전하게 읽기
            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                logger.error("MCP Server 응답이 비어있음")
                throw IOException("Empty response body")
            }
            
            logger.debug("MCP 응답 길이: ${responseBody.length}자")
            logger.debug("MCP 응답 마지막 100자: ${responseBody.takeLast(100)}")
            
            try {
                return objectMapper.readValue(responseBody, MCPResponse::class.java)
            } catch (e: Exception) {
                logger.error("MCP 응답 파싱 실패: ${e.message}")
                logger.error("파싱 실패한 응답 전체: $responseBody")
                throw e
            }
        }
    }

    /**
     * MCP 응답을 TravelPlanResponse로 변환
     */
    private fun convertToTravelPlanResponse(mcpResponse: MCPResponse, originalRequest: TravelPlanRequest): TravelPlanResponse {
        if (mcpResponse.error != null) {
            logger.error("MCP 서버 에러: ${mcpResponse.error.message}")
            throw RuntimeException("MCP 서버 에러: ${mcpResponse.error.message}")
        }

        val result = mcpResponse.result 
            ?: throw RuntimeException("MCP 응답에 결과가 없습니다")

        // MCP 응답에서 JSON 파싱
        val responseText = result.content.firstOrNull()?.text 
            ?: throw RuntimeException("MCP 응답 내용이 비어있습니다")

        return try {
            // 안전한 JSON 파싱
            val cleanedJson = cleanJsonResponse(responseText)
            val mcpTravelResponse = objectMapper.readValue(cleanedJson, MCPTravelResponse::class.java)
            
            logger.info("MCP 응답 파싱 성공: ${mcpTravelResponse.destination}")
            
            TravelPlanResponse(
                destination = mcpTravelResponse.destination,
                duration = originalRequest.duration,
                totalBudget = mcpTravelResponse.totalCost,
                itinerary = mcpTravelResponse.itinerary.map { convertToDayPlan(it) },
                summary = createTravelSummary(mcpTravelResponse, originalRequest)
            )
        } catch (e: Exception) {
            logger.warn("MCP 응답 파싱 실패, 기본 응답 생성: ${e.message}")
            logger.debug("파싱 실패한 응답 내용: ${responseText.take(500)}...")
            return createFallbackResponse(originalRequest)
        }
    }
    
    /**
     * JSON 응답 정리
     */
    private fun cleanJsonResponse(jsonText: String): String {
        return try {
            var cleaned = jsonText.trim()
            
            // 마크다운 코드 블록 제거
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.removePrefix("```json").trim()
            }
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.removePrefix("```").trim()
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.removeSuffix("```").trim()
            }
            
            // 불완전한 JSON 수정 시도
            if (!cleaned.endsWith("}")) {
                // JSON이 중간에 잘린 경우 기본 종료 추가
                val openBraces = cleaned.count { it == '{' }
                val closeBraces = cleaned.count { it == '}' }
                val missingBraces = openBraces - closeBraces
                
                if (missingBraces > 0) {
                    cleaned += "}".repeat(missingBraces)
                    logger.info("불완전한 JSON 수정: ${missingBraces}개 중괄호 추가")
                }
            }
            
            // JSON 유효성 검증
            objectMapper.readTree(cleaned)
            logger.debug("JSON 정리 완료: ${cleaned.length}자")
            cleaned
            
        } catch (e: Exception) {
            logger.error("JSON 정리 실패: ${e.message}")
            throw e
        }
    }

    /**
     * MCPDayPlan을 DayPlan으로 변환
     */
    private fun convertToDayPlan(mcpDayPlan: MCPDayPlan): DayPlan {
        val activities = mcpDayPlan.activities.map { mcpActivity ->
            Activity(
                time = mcpActivity.time,
                type = mapActivityType(mcpActivity.type),
                name = mcpActivity.name,
                description = mcpActivity.description,
                location = Location(
                    lat = mcpActivity.location.lat,
                    lng = mcpActivity.location.lng,
                    address = mcpActivity.location.address
                ),
                duration = mcpActivity.duration,
                cost = mcpActivity.cost,
                rating = mcpActivity.rating
            )
        }

        return DayPlan(
            day = mcpDayPlan.day,
            date = null, // 날짜는 나중에 계산
            activities = activities,
            dailyCost = activities.sumOf { it.cost }
        )
    }

    /**
     * 문자열을 ActivityType enum으로 변환
     */
    private fun mapActivityType(typeString: String): ActivityType {
        return when (typeString.lowercase()) {
            "attraction", "tourist_attraction" -> ActivityType.ATTRACTION
            "restaurant", "food" -> ActivityType.RESTAURANT
            "shopping" -> ActivityType.SHOPPING
            "transport", "transportation" -> ActivityType.TRANSPORT
            "break", "rest" -> ActivityType.BREAK
            else -> ActivityType.ATTRACTION
        }
    }

    /**
     * TravelSummary 생성
     */
    private fun createTravelSummary(mcpResponse: MCPTravelResponse, request: TravelPlanRequest): TravelSummary {
        val allActivities = mcpResponse.itinerary.flatMap { it.activities }
        val typeCount = allActivities.groupingBy { mapActivityType(it.type) }.eachCount()
        val avgRating = allActivities.mapNotNull { it.rating }.average().takeIf { !it.isNaN() }

        return TravelSummary(
            totalCost = mcpResponse.totalCost,
            totalActivities = allActivities.size,
            typeCount = typeCount,
            averageRating = avgRating
        )
    }

    /**
     * 에러 시 기본 응답 생성
     */
    private fun createFallbackResponse(request: TravelPlanRequest): TravelPlanResponse {
        logger.info("기본 여행 계획 생성 중...")
        
        val dailyBudget = request.dailyBudget ?: 100000L
        val destinationLocation = getLocationByDestination(request.destination)
        
        val sampleActivities = listOf(
            Activity(
                time = "09:00",
                type = ActivityType.ATTRACTION,
                name = "${request.destination} 주요 관광지",
                description = "현지 대표 관광지 방문",
                location = destinationLocation,
                duration = 120,
                cost = dailyBudget / 3,
                rating = 4.5
            ),
            Activity(
                time = "12:00",
                type = ActivityType.RESTAURANT,
                name = "현지 맛집",
                description = "지역 특색 음식 체험",
                location = destinationLocation.copy(address = "${request.destination} 맛집거리"),
                duration = 90,
                cost = dailyBudget / 3,
                rating = 4.2
            ),
            Activity(
                time = "15:00",
                type = ActivityType.SHOPPING,
                name = "쇼핑 지역",
                description = "현지 쇼핑 및 기념품 구매",
                location = destinationLocation.copy(address = "${request.destination} 쇼핑가"),
                duration = 120,
                cost = dailyBudget / 3,
                rating = 4.0
            )
        )

        val itinerary = (1..request.duration).map { day ->
            DayPlan(
                day = day,
                date = null,
                activities = sampleActivities,
                dailyCost = dailyBudget
            )
        }

        return TravelPlanResponse(
            destination = request.destination,
            duration = request.duration,
            totalBudget = dailyBudget * request.duration,
            itinerary = itinerary,
            summary = TravelSummary(
                totalCost = dailyBudget * request.duration,
                totalActivities = sampleActivities.size * request.duration,
                typeCount = mapOf(
                    ActivityType.ATTRACTION to request.duration,
                    ActivityType.RESTAURANT to request.duration,
                    ActivityType.SHOPPING to request.duration
                ),
                averageRating = 4.2
            )
        )
    }

    /**
     * 목적지별 정확한 위치 정보 반환
     */
    private fun getLocationByDestination(destination: String): Location {
        return when (destination.lowercase().replace(" ", "")) {
            "서울", "seoul" -> Location(37.5665, 126.9780, "서울특별시")
            "부산", "busan" -> Location(35.1796, 129.0756, "부산광역시")
            "제주도", "제주", "jeju" -> Location(33.4996, 126.5312, "제주특별자치도")
            "대구", "daegu" -> Location(35.8714, 128.6014, "대구광역시")
            "인천", "incheon" -> Location(37.4563, 126.7052, "인천광역시")
            "광주", "gwangju" -> Location(35.1595, 126.8526, "광주광역시")
            "대전", "daejeon" -> Location(36.3504, 127.3845, "대전광역시")
            "울산", "ulsan" -> Location(35.5384, 129.3114, "울산광역시")
            "경주", "gyeongju" -> Location(35.8562, 129.2247, "경상북도 경주시")
            "전주", "jeonju" -> Location(35.8242, 127.1479, "전라북도 전주시")
            "강릉", "gangneung" -> Location(37.7519, 128.8761, "강원도 강릉시")
            "여수", "yeosu" -> Location(34.7604, 127.6622, "전라남도 여수시")
            "춘천", "chuncheon" -> Location(37.8813, 127.7298, "강원도 춘천시")
            "속초", "sokcho" -> Location(38.2070, 128.5918, "강원도 속초시")
            "안동", "andong" -> Location(36.5684, 128.7294, "경상북도 안동시")
            "포항", "pohang" -> Location(36.0190, 129.3435, "경상북도 포항시")
            "목포", "mokpo" -> Location(34.8118, 126.3921, "전라남도 목포시")
            "순천", "suncheon" -> Location(34.9506, 127.4872, "전라남도 순천시")
            "창원", "changwon" -> Location(35.2280, 128.6811, "경상남도 창원시")
            "진주", "jinju" -> Location(35.1801, 128.1076, "경상남도 진주시")
            "원주", "wonju" -> Location(37.3422, 127.9202, "강원도 원주시")
            "청주", "cheongju" -> Location(36.6424, 127.4890, "충청북도 청주시")
            "천안", "cheonan" -> Location(36.8151, 127.1139, "충청남도 천안시")
            // 해외 도시
            "도쿄", "tokyo" -> Location(35.6762, 139.6503, "일본 도쿄")
            "오사카", "osaka" -> Location(34.6937, 135.5023, "일본 오사카")
            "뉴욕", "newyork" -> Location(40.7128, -74.0060, "미국 뉴욕")
            "파리", "paris" -> Location(48.8566, 2.3522, "프랑스 파리")
            "런던", "london" -> Location(51.5074, -0.1278, "영국 런던")
            "상하이", "shanghai" -> Location(31.2304, 121.4737, "중국 상하이")
            "비엔나", "vienna" -> Location(48.2082, 16.3738, "오스트리아 비엔나")
            "로마", "rome" -> Location(41.9028, 12.4964, "이탈리아 로마")
            "바르셀로나", "barcelona" -> Location(41.3851, 2.1734, "스페인 바르셀로나")
            "방콕", "bangkok" -> Location(13.7563, 100.5018, "태국 방콕")
            else -> {
                logger.warn("알 수 없는 목적지: $destination, 기본 위치(서울) 사용")
                Location(37.5665, 126.9780, destination)
            }
        }
    }
}