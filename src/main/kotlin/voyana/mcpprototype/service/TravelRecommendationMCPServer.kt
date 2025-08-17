package voyana.mcpprototype.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import voyana.mcpprototype.client.mcp.*
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * MCP Server 구현체
 * - HTTP 서버로 동작하며 MCP 프로토콜을 처리
 * - Google Places API와 Ollama를 활용하여 여행 계획 생성
 */
@Component
class TravelRecommendationMCPServer(
    @Value("\${google.places.api-key:}")
    private val googlePlacesApiKey: String,
    @Value("\${ollama.base-url:http://localhost:11434}")
    private val ollamaBaseUrl: String,
    @Value("\${ollama.model}")
    private val ollamaModel: String
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(TravelRecommendationMCPServer::class.java)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(180000, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180000, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val executor = Executors.newFixedThreadPool(10)
    private var serverSocket: ServerSocket? = null

    override fun run(vararg args: String?) {
        // Spring Boot 시작 후 자동으로 MCP 서버 시작
        Thread { startMCPServer() }.start()
    }

    /**
     * MCP 서버 시작
     */
    fun startMCPServer() {
        try {
            serverSocket = ServerSocket(8081) // MCP 서버는 8081 포트 사용
            logger.info("MCP 서버가 포트 8081에서 시작되었습니다...")

            while (!serverSocket!!.isClosed) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    executor.submit { handleClient(clientSocket) }
                } catch (e: IOException) {
                    if (!serverSocket!!.isClosed) {
                        logger.error("클라이언트 연결 처리 중 오류: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("MCP 서버 시작 실패: ${e.message}", e)
        }
    }

    /**
     * 클라이언트 요청 처리
     */
    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.getInputStream().bufferedReader().use { reader ->
                clientSocket.getOutputStream().bufferedWriter().use { writer ->
                    
                    // HTTP 요청 파싱 (간단한 구현)
                    val requestLine = reader.readLine()
                    if (requestLine?.contains("POST /mcp") == true) {
                        
                        // 헤더 읽기
                        var contentLength = 0
                        var line = reader.readLine()
                        while (line?.isNotEmpty() == true) {
                            if (line.startsWith("Content-Length:")) {
                                contentLength = line.substringAfter(":").trim().toInt()
                            }
                            line = reader.readLine()
                        }

                        // Body 읽기 - 더 안전하게
                        val requestBody = if (contentLength > 0) {
                            val body = CharArray(contentLength)
                            var totalRead = 0
                            while (totalRead < contentLength) {
                                val bytesRead = reader.read(body, totalRead, contentLength - totalRead)
                                if (bytesRead == -1) break
                                totalRead += bytesRead
                            }
                            String(body, 0, totalRead)
                        } else {
                            ""
                        }

                        // MCP 요청 처리
                        val response = handleMCPRequest(requestBody)

                        // HTTP 응답 전송 - 더 안전하게
                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Type: application/json; charset=UTF-8\r\n")
                        writer.write("Content-Length: ${response.toByteArray(Charsets.UTF_8).size}\r\n")
                        writer.write("Connection: close\r\n")
                        writer.write("\r\n")
                        writer.write(response)
                        writer.flush()
                        
                        logger.debug("응답 전송 완료: ${response.length}자")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("클라이언트 요청 처리 오류: ${e.message}", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: IOException) {
                logger.debug("소켓 종료 오류: ${e.message}")
            }
        }
    }

    /**
     * MCP 요청 처리
     */
    private fun handleMCPRequest(requestBody: String): String {
        return try {
            logger.debug("MCP 요청 수신: $requestBody")
            
            val mcpRequest = objectMapper.readValue(requestBody, MCPRequest::class.java)
            
            when (mcpRequest.method) {
                "tools/call" -> {
                    when (mcpRequest.params.name) {
                        "travel_planner" -> handleTravelPlanning(mcpRequest)
                        else -> createErrorResponse(mcpRequest.id, "Unknown tool: ${mcpRequest.params.name}")
                    }
                }
                else -> createErrorResponse(mcpRequest.id, "Unknown method: ${mcpRequest.method}")
            }
        } catch (e: Exception) {
            logger.error("MCP 요청 처리 오류: ${e.message}", e)
            createErrorResponse("unknown", "Request processing error: ${e.message}")
        }
    }

    /**
     * 여행 계획 생성 처리
     */
    private fun handleTravelPlanning(mcpRequest: MCPRequest): String {
        return try {
            val arguments = mcpRequest.params.arguments
            val destination = arguments["destination"] as? String ?: "서울"
            val duration = (arguments["duration"] as? Number)?.toInt() ?: 3
            val dailyBudget = (arguments["daily_budget"] as? Number)?.toLong() ?: 100000L
            val intensity = arguments["intensity"] as? String ?: "medium"
            val preferences = arguments["preferences"] as? String ?: ""

            logger.info("여행 계획 생성 시작: $destination, ${duration}일, 예산: ${dailyBudget}원")

            // 1. Google Places API로 장소 검색
            val placesData = searchGooglePlaces(destination, intensity)
            
            // 2. Ollama로 여행 계획 생성
            val travelPlan = generateTravelPlanWithOllama(
                destination, duration, dailyBudget, intensity, preferences, placesData
            )

            // 3. MCP 응답 생성
            createSuccessResponse(mcpRequest.id, travelPlan)

        } catch (e: Exception) {
            logger.error("여행 계획 생성 오류: ${e.message}", e)
            createErrorResponse(mcpRequest.id, "Travel planning failed: ${e.message}")
        }
    }

    /**
     * Google Places API 검색
     */
    private fun searchGooglePlaces(destination: String, intensity: String): String {
        if (googlePlacesApiKey.isBlank()) {
            logger.warn("Google Places API 키가 설정되지 않았습니다. 샘플 데이터를 사용합니다.")
            return createSamplePlacesData(destination)
        }

        try {
            val types = when (intensity) {
                "low" -> listOf("restaurant", "cafe")
                "medium" -> listOf("tourist_attraction", "restaurant")
                "high" -> listOf("tourist_attraction", "restaurant", "shopping_mall")
                else -> listOf("tourist_attraction", "restaurant")
            }

            val allPlaces = mutableListOf<String>()
            
            types.forEach { type ->
                val places = searchPlacesByType(destination, type)
                allPlaces.addAll(places)
            }

            return allPlaces.take(10).joinToString("\n")

        } catch (e: Exception) {
            logger.error("Google Places API 호출 실패: ${e.message}")
            return createSamplePlacesData(destination)
        }
    }

    /**
     * 타입별 장소 검색
     */
    private fun searchPlacesByType(destination: String, type: String): List<String> {
        val encodedDestination = java.net.URLEncoder.encode(destination, "UTF-8")
        val url = "https://maps.googleapis.com/maps/api/place/textsearch/json" +
                "?query=$encodedDestination&type=$type&key=$googlePlacesApiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Google Places API 호출 실패: ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            val jsonResponse = JSONObject(responseBody)
            val results = jsonResponse.optJSONArray("results")

            if (results == null || results.length() == 0) {
                return emptyList()
            }

            val places = mutableListOf<String>()
            for (i in 0 until minOf(3, results.length())) {
                val place = results.getJSONObject(i)
                val name = place.optString("name", "")
                val rating = place.optDouble("rating", 0.0)
                val address = place.optString("formatted_address", "")
                val priceLevel = place.optInt("price_level", 2)
                
                val geometry = place.optJSONObject("geometry")
                val location = geometry?.optJSONObject("location")
                val lat = location?.optDouble("lat", 0.0) ?: 0.0
                val lng = location?.optDouble("lng", 0.0) ?: 0.0

                places.add("$name (평점: $rating, 가격대: $priceLevel, 위치: $lat,$lng) - $address")
            }
            return places
        }
    }

    /**
     * 샘플 장소 데이터 생성
     */
    private fun createSamplePlacesData(destination: String): String {
        return """
            ${destination} 대표 관광지 (평점: 4.5, 가격대: 1, 위치: 37.5665,126.9780) - $destination 중심가
            ${destination} 전통 시장 (평점: 4.2, 가격대: 2, 위치: 37.5665,126.9780) - $destination 전통시장
            ${destination} 맛집거리 (평점: 4.3, 가격대: 2, 위치: 37.5665,126.9780) - $destination 음식점가
            ${destination} 쇼핑센터 (평점: 4.0, 가격대: 3, 위치: 37.5665,126.9780) - $destination 쇼핑가
            ${destination} 공원 (평점: 4.4, 가격대: 0, 위치: 37.5665,126.9780) - $destination 자연공원
        """.trimIndent()
    }

    /**
     * Ollama로 여행 계획 생성
     */
    private fun generateTravelPlanWithOllama(
        destination: String,
        duration: Int,
        dailyBudget: Long,
        intensity: String,
        preferences: String,
        placesData: String
    ): String {
        logger.info("여행 계획 생성 시작: $destination, ${duration}일")

        // 더 간단한 프롬프트로 수정 (속도 개선)
        val prompt = """
            $destination ${duration}일 여행 계획을 JSON으로 만들어주세요.
            예산: 하루 ${dailyBudget}원
            스타일: $intensity

            매우 간단하게 하루에 활동 2개씩만:
            {
              "destination": "$destination",
              "itinerary": [
                {
                  "day": 1,
                  "activities": [
                    {
                      "time": "10:00",
                      "name": "관광지",
                      "type": "attraction",
                      "description": "대표 관광지",
                      "cost": 50000,
                      "location": {"lat": 37.5665, "lng": 126.9780, "address": "$destination"},
                      "duration": 120,
                      "rating": 4.5
                    },
                    {
                      "time": "14:00",
                      "name": "맛집",
                      "type": "restaurant",
                      "description": "현지 음식",
                      "cost": 30000,
                      "location": {"lat": 37.5665, "lng": 126.9780, "address": "$destination"},
                      "duration": 90,
                      "rating": 4.2
                    }
                  ]
                }
              ],
              "totalCost": ${dailyBudget * duration},
              "summary": "$destination ${duration}일 여행"
            }

            JSON만 응답하세요. 설명 생략.
        """.trimIndent()

            // 30초 내에 응답이 오지 않으면 Fallback 사용
            val result = callOllamaAPI(prompt)

            if (result.isNullOrBlank()) {
                logger.warn("Ollama 응답이 비어있음, Fallback 사용")
            }
            
            // JSON 검증
            return try {
                JSONObject(result?.replace("```json", "")?.replace("```", "")?.trimIndent())
                logger.info("Ollama JSON 검증 성공")
                result
            } catch (e: Exception) {
                logger.warn("Ollama 응답이 올바른 JSON이 아님, Fallback 사용: ${e.message}")
                throw  e
            } ?: ""
    }

    /**
     * Ollama API 호출
     */
    private fun callOllamaAPI(prompt: String): String? {
        return try {
            logger.info("Ollama API 호출 시작...")
            val json = JSONObject().apply {
                put("model", ollamaModel)
                put("prompt", prompt)
                put("stream", false)
                put("options", JSONObject().apply {
                    put("temperature", 0.7)
                    put("top_p", 0.9)
                    put("num_predict", 100000)  // 최대 토큰 수 제한
                })
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$ollamaBaseUrl/api/generate")
                .post(requestBody)
                .build()

            logger.debug("Ollama 요청: ${json.toString()}")

            httpClient.newCall(request).execute().use { response ->
                logger.info("Ollama 응답 코드: ${response.code}")
                
                if (!response.isSuccessful) {
                    logger.error("Ollama API 호출 실패: ${response.code} - ${response.message}")
                    return null
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    logger.error("Ollama 응답이 비어있음")
                    return null
                }
                
                logger.debug("Ollama 응답 일부: ${responseBody.take(200)}...")
                
                val jsonResponse = JSONObject(responseBody)
                val result = jsonResponse.optString("response", null)
                
                if (result.isNullOrBlank()) {
                    logger.error("Ollama 응답에 'response' 필드가 없음")
                    return null
                }
                
                logger.info("Ollama API 호출 성공")
                result
            }
        } catch (e: Exception) {
            logger.error("Ollama API 호출 중 오류: ${e.message}", e)
            null
        }
    }


    /**
     * 대체 여행 계획 생성
     */
    /*private fun createFallbackTravelPlan(destination: String, duration: Int, dailyBudget: Long): String {
        val activities = mutableListOf<Map<String, Any>>()
        
        for (day in 1..duration) {
            val dayActivities = listOf(
                mapOf(
                    "time" to "09:00",
                    "name" to "$destination 주요 관광지",
                    "type" to "attraction",
                    "description" to "대표 관광지 방문",
                    "cost" to (dailyBudget * 0.4).toLong(),
                    "location" to mapOf("lat" to 37.5665, "lng" to 126.9780, "address" to destination),
                    "duration" to 120,
                    "rating" to 4.5
                ),
                mapOf(
                    "time" to "12:00",
                    "name" to "$destination 현지 맛집",
                    "type" to "restaurant",
                    "description" to "지역 특색 음식",
                    "cost" to (dailyBudget * 0.3).toLong(),
                    "location" to mapOf("lat" to 37.5665, "lng" to 126.9780, "address" to destination),
                    "duration" to 90,
                    "rating" to 4.2
                ),
                mapOf(
                    "time" to "15:00",
                    "name" to "$destination 쇼핑 지역",
                    "type" to "shopping",
                    "description" to "기념품 구매",
                    "cost" to (dailyBudget * 0.3).toLong(),
                    "location" to mapOf("lat" to 37.5665, "lng" to 126.9780, "address" to destination),
                    "duration" to 120,
                    "rating" to 4.0
                )
            )
            activities.add(mapOf("day" to day, "activities" to dayActivities))
        }

        val fallbackPlan = mapOf(
            "destination" to destination,
            "itinerary" to activities,
            "totalCost" to (dailyBudget * duration),
            "summary" to "$destination ${duration}일 여행 계획 (일일 예산: ${dailyBudget}원)"
        )

        return objectMapper.writeValueAsString(fallbackPlan)
    }*/
    /**
     * 성공 응답 생성
     */
    private fun createSuccessResponse(id: String, content: String): String {
        try {
            // JSON 바로 되돌리기 전에 첫 번째로 정리
            val cleanContent = cleanJsonContent(content)
            
            val response = MCPResponse(
                id = id,
                result = MCPResult(
                    content = listOf(MCPContent(type = "text", text = cleanContent)),
                    isError = false
                )
            )
            return objectMapper.writeValueAsString(response)
        } catch (e: Exception) {
            logger.error("성공 응답 생성 오류: ${e.message}")
            // JSON 생성 실패 시 Fallback JSON 사용
            return createFallbackSuccessResponse(id)
        }
    }
    
    /**
     * JSON 콘텐츠 정리
     */
    private fun cleanJsonContent(content: String): String {
        return try {
            // ```json ``` 마크다운 제거
            var cleaned = content.trim()
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.removePrefix("```json").trim()
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.removeSuffix("```").trim()
            }
            
            // JSON 유효성 검증
            JSONObject(cleaned)
            logger.info("JSON 콘텐츠 정리 성공")
            cleaned
        } catch (e: Exception) {
            logger.warn("JSON 콘텐츠 정리 실패: ${e.message}")
            // 유효하지 않은 JSON인 경우 기본 JSON 반환
            createBasicJsonResponse("Unknown destination", 1, 100000)
        }
    }
    
    /**
     * 기본 JSON 응답 생성
     */
    private fun createBasicJsonResponse(destination: String, duration: Int, dailyBudget: Long): String {
        val basicPlan = mapOf(
            "destination" to destination,
            "itinerary" to (1..duration).map { day ->
                mapOf(
                    "day" to day,
                    "activities" to listOf(
                        mapOf(
                            "time" to "09:00",
                            "name" to "$destination 관광지",
                            "type" to "attraction",
                            "description" to "대표 관광지 방문",
                            "cost" to dailyBudget,
                            "location" to mapOf(
                                "lat" to 37.5665,
                                "lng" to 126.9780,
                                "address" to destination
                            ),
                            "duration" to 120,
                            "rating" to 4.5
                        )
                    )
                )
            },
            "totalCost" to (dailyBudget * duration),
            "summary" to "$destination ${duration}일 여행 계획"
        )
        return objectMapper.writeValueAsString(basicPlan)
    }
    
    /**
     * Fallback 성공 응답
     */
    private fun createFallbackSuccessResponse(id: String): String {
        val fallbackJson = createBasicJsonResponse("서울", 3, 100000)
        val response = MCPResponse(
            id = id,
            result = MCPResult(
                content = listOf(MCPContent(type = "text", text = fallbackJson)),
                isError = false
            )
        )
        return objectMapper.writeValueAsString(response)
    }

    /**
     * 에러 응답 생성
     */
    private fun createErrorResponse(id: String, message: String): String {
        val response = MCPResponse(
            id = id,
            error = MCPError(
                code = -1,
                message = message
            )
        )
        return objectMapper.writeValueAsString(response)
    }

    /**
     * 서버 종료
     */
    fun stopServer() {
        try {
            serverSocket?.close()
            executor.shutdown()
            logger.info("MCP 서버가 종료되었습니다.")
        } catch (e: Exception) {
            logger.error("서버 종료 중 오류: ${e.message}")
        }
    }
}