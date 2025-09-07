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
import java.util.concurrent.TimeUnit

@Component
class MCPClient(
    @Value("\${mcp.server.url:http://localhost:8081}")
    private val mcpServerUrl: String
) {
    private val logger = LoggerFactory.getLogger(MCPClient::class.java)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * MCP Server에 여행 계획 생성 요청 (기본 응답 완전 제거)
     */
    fun generateTravelPlan(request: TravelPlanRequest): TravelPlanResponse {
        logger.info("MCP Client: 여행 계획 생성 요청 시작 - 목적지: ${request.destination}")

        // 기본 응답 완전 제거 - 실패 시 예외 던지기
        val mcpTravelRequest = MCPTravelRequest(
            destination = request.destination,
            duration = request.duration,
            dailyBudget = request.dailyBudget,
            intensity = request.intensity,
            preferences = request.preferences
        )

        val mcpRequest = MCPRequest(
            method = "tools/call",
            params = MCPParams(
                name = "travel_planner",
                arguments = mcpTravelRequest.toMCPArguments()
            ),
            id = UUID.randomUUID().toString()
        )

        val mcpResponse = callMCPServerWithRetry(mcpRequest, maxRetries = 2)

        return convertToTravelPlanResponse(mcpResponse, request)
    }

    private fun callMCPServerWithRetry(mcpRequest: MCPRequest, maxRetries: Int = 2): MCPResponse {
        var lastException: Exception? = null

        repeat(maxRetries + 1) { attempt ->
            try {
                logger.info("MCP Server 호출 시도 ${attempt + 1}/${maxRetries + 1}")
                return callMCPServer(mcpRequest)
            } catch (e: Exception) {
                lastException = e
                logger.warn("MCP Server 호출 실패 (시도 ${attempt + 1}): ${e.message}")

                if (attempt < maxRetries) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            }
        }

        throw lastException ?: RuntimeException("MCP Server 호출 실패")
    }

    private fun callMCPServer(mcpRequest: MCPRequest): MCPResponse {
        val jsonBody = objectMapper.writeValueAsString(mcpRequest)
        logger.debug("MCP 요청 JSON 길이: ${jsonBody.length}자")

        val requestBody = jsonBody.toRequestBody("application/json; charset=UTF-8".toMediaType())
        val httpRequest = Request.Builder()
            .url("$mcpServerUrl/mcp")
            .post(requestBody)
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .addHeader("Accept", "application/json")
            .addHeader("Connection", "close")
            .build()

        logger.info("MCP Server 요청 전송: $mcpServerUrl/mcp")

        httpClient.newCall(httpRequest).execute().use { response ->
            logger.info("MCP Server 응답: ${response.code} ${response.message}")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "No error body"
                logger.error("MCP Server 호출 실패: ${response.code} - ${response.message}")
                logger.error("에러 응답 내용: $errorBody")
                throw IOException("MCP Server 호출 실패: ${response.code} - ${response.message}")
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                logger.error("MCP Server 응답이 비어있음")
                throw IOException("Empty response body")
            }

            logger.info("MCP 응답 성공: ${responseBody.length}자")

            try {
                return objectMapper.readValue(responseBody, MCPResponse::class.java)
            } catch (e: Exception) {
                logger.error("MCP 응답 파싱 실패: ${e.message}")
                logger.error("파싱 실패한 응답 내용: ${responseBody.take(500)}...")
                throw IOException("응답 파싱 실패: ${e.message}")
            }
        }
    }

    private fun convertToTravelPlanResponse(mcpResponse: MCPResponse, originalRequest: TravelPlanRequest): TravelPlanResponse {
        if (mcpResponse.error != null) {
            logger.error("MCP 서버 에러: ${mcpResponse.error.message}")
            throw RuntimeException("MCP 서버 에러: ${mcpResponse.error.message}")
        }

        val result = mcpResponse.result
            ?: throw RuntimeException("MCP 응답에 결과가 없습니다")

        val responseText = result.content.firstOrNull()?.text
            ?: throw RuntimeException("MCP 응답 내용이 비어있습니다")

        // 기본 응답 생성 제거 - 파싱 실패 시 예외 던지기
        val cleanedJson = cleanJsonResponse(responseText)
        val mcpTravelResponse = objectMapper.readValue(cleanedJson, MCPTravelResponse::class.java)

        logger.info("MCP 응답 파싱 성공: ${mcpTravelResponse.destination}")

        return TravelPlanResponse(
            destination = mcpTravelResponse.destination,
            duration = originalRequest.duration,
            totalBudget = mcpTravelResponse.totalCost,
            itinerary = mcpTravelResponse.itinerary.map { convertToDayPlan(it) },
            summary = createTravelSummary(mcpTravelResponse, originalRequest)
        )
    }

    private fun cleanJsonResponse(jsonText: String): String {
        var cleaned = jsonText.trim()

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.removePrefix("```json").trim()
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```").trim()
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.removeSuffix("```").trim()
        }

        if (!cleaned.endsWith("}")) {
            val openBraces = cleaned.count { it == '{' }
            val closeBraces = cleaned.count { it == '}' }
            val missingBraces = openBraces - closeBraces

            if (missingBraces > 0) {
                cleaned += "}".repeat(missingBraces)
                logger.info("불완전한 JSON 수정: ${missingBraces}개 중괄호 추가")
            }
        }

        objectMapper.readTree(cleaned)
        logger.debug("JSON 정리 완료: ${cleaned.length}자")
        return cleaned
    }

    private fun convertToDayPlan(mcpDayPlan: MCPDayPlan): DayPlan {
        val activities = mcpDayPlan.activities.map { mcpActivity ->
            Activity(
                time = mcpActivity.time,
                type = mapActivityType(mcpActivity.type),
                name = mcpActivity.name,
                description = mcpActivity.description ?: mcpActivity.brief ?: "기본 설명",
                location = Location(
                    lat = mcpActivity.location?.lat ?: 37.5665,
                    lng = mcpActivity.location?.lng ?: 126.9780,
                    address = mcpActivity.location?.address ?: mcpActivity.name
                ),
                duration = mcpActivity.duration ?: 120,
                cost = mcpActivity.cost ?: 50000L,
                rating = mcpActivity.rating ?: 4.0
            )
        }

        return DayPlan(
            day = mcpDayPlan.day,
            date = null,
            activities = activities,
            dailyCost = activities.sumOf { it.cost }
        )
    }

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

    private fun getLocationByDestination(destination: String): Location {
        return when (destination.lowercase().replace(" ", "")) {
            "서울", "seoul" -> Location(37.5665, 126.9780, "서울특별시")
            "부산", "busan" -> Location(35.1796, 129.0756, "부산광역시")
            "제주도", "제주", "jeju" -> Location(33.4996, 126.5312, "제주특별자치도")
            else -> {
                logger.warn("알 수 없는 목적지: $destination, 기본 위치(서울) 사용")
                Location(37.5665, 126.9780, destination)
            }
        }
    }
}