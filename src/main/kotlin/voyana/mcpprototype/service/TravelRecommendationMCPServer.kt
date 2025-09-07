package voyana.mcpprototype.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import voyana.mcpprototype.client.mcp.*
import voyana.mcpprototype.service.places.PlacesSearchService
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

@Component
class TravelRecommendationMCPServer(
    @Value("\${google.places.api-key:}")
    private val googlePlacesApiKey: String,
    @Value("\${ollama.base-url:http://localhost:11434}")
    private val ollamaBaseUrl: String,
    @Value("\${ollama.model}")
    private val ollamaModel: String,
    private val placesSearchService: PlacesSearchService  // 새로 추가
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(TravelRecommendationMCPServer::class.java)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val placesHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val executor = Executors.newFixedThreadPool(20)
    private var serverSocket: ServerSocket? = null

    private data class PlaceLite(
        val id: String,
        val name: String,
        val lat: Double,
        val lng: Double,
        val rating: Double?,
        val userRatings: Int?,
        val priceLevel: Int?,
        val types: List<String>,
        val openNow: Boolean?
    )

    override fun run(vararg args: String?) {
        logger.info("start MCP Server")
        Thread { startMCPServer() }.start()
    }
    
    /**
     * 실제 장소 데이터를 JSON으로 변환
     */
    private fun buildRealPlacesJson(places: List<voyana.mcpprototype.service.places.FilteredPlace>, destination: String): String {
        val root = JSONObject()
        root.put("destination", destination)
        
        val candidatesArray = JSONArray()
        
        // 타입별로 그룹징하여 균형있게 배치
        val restaurants = places.filter { it.type == "restaurant" }.take(5)
        val attractions = places.filter { it.type == "tourist_attraction" }.take(4)
        val cafes = places.filter { it.type == "cafe" }.take(3)
        
        (restaurants + attractions + cafes).forEach { place ->
            candidatesArray.put(JSONObject().apply {
                put("id", place.placeId)
                put("name", place.name)
                put("type", place.type)
                put("rating", place.rating ?: 4.0)
                put("userRatings", place.userRatingsTotal ?: 0)
                put("priceLevel", place.priceLevel ?: 2)
                put("lat", place.lat)
                put("lng", place.lng)
                put("address", place.address)
                put("distance", place.distance.toInt())
            })
        }
        
        root.put("candidates", candidatesArray)
        root.put("totalCount", candidatesArray.length())
        
        logger.info("실제 장소 JSON 생성: 레스토랑 ${restaurants.size}개, 관광지 ${attractions.size}개, 카페 ${cafes.size}개")
        
        return root.toString()
    }
    
    /**
     * 목적지별 좌표 반환
     */
    private fun getCoordinatesForDestination(destination: String): Pair<Double, Double> {
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

    fun startMCPServer() {
        try {
            serverSocket = ServerSocket(8081)
            serverSocket!!.soTimeout = 1000
            logger.info("MCP 서버가 포트 8081에서 시작되었습니다...")

            while (!serverSocket!!.isClosed) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    clientSocket.soTimeout = 10000
                    executor.submit { handleClientSafely(clientSocket) }
                } catch (e: IOException) {
                }
            }
        } catch (e: Exception) {
            logger.error("MCP 서버 시작 실패: ${e.message}", e)
        }
    }

    private fun handleClientSafely(clientSocket: Socket) {
        val future = CompletableFuture.supplyAsync {
            handleClient(clientSocket)
        }

        try {
            future.get(120, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            logger.error("클라이언트 처리 타임아웃 (120 초 초과)")
            future.cancel(true)
        } catch (e: Exception) {
            logger.error("클라이언트 처리 중 예외: ${e.message}")
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                logger.debug("소켓 종료 오류: ${e.message}")
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.getInputStream().bufferedReader().use { reader ->
                clientSocket.getOutputStream().bufferedWriter().use { writer ->

                    val requestLine = reader.readLine()
                    if (requestLine?.contains("POST /mcp") != true) {
                        sendBadRequestResponse(writer)
                        return
                    }

                    var contentLength = 0
                    var line = reader.readLine()
                    while (line?.isNotEmpty() == true) {
                        if (line.startsWith("Content-Length:")) {
                            contentLength = line.substringAfter(":").trim().toInt()
                        }
                        line = reader.readLine()
                    }

                    if (contentLength <= 0) {
                        sendBadRequestResponse(writer)
                        return
                    }

                    if (contentLength > 10000) {
                        sendBadRequestResponse(writer, "Request too large")
                        return
                    }

                    val requestBody = readRequestBody(reader, contentLength)
                    if (requestBody.isEmpty()) {
                        sendBadRequestResponse(writer, "Empty request body")
                        return
                    }

                    val response = handleMCPRequestFast(requestBody)
                    sendSuccessResponse(writer, response)
                }
            }
        } catch (e: Exception) {
            logger.error("클라이언트 요청 처리 오류: ${e.message}", e)
        }
    }

    private fun readRequestBody(reader: java.io.BufferedReader, contentLength: Int): String {
        return try {
            val buffer = StringBuilder()
            var totalRead = 0
            var attempts = 0
            val maxAttempts = 50

            while (totalRead < contentLength && attempts < maxAttempts) {
                if (reader.ready()) {
                    val ch = reader.read()
                    if (ch == -1) break
                    buffer.append(ch.toChar())
                    totalRead++
                } else {
                    Thread.sleep(10)
                    attempts++
                }
            }

            val result = buffer.toString()
            logger.debug("요청 본문 읽기 완료: ${result.length}/${contentLength}자")
            result
        } catch (e: Exception) {
            logger.error("요청 본문 읽기 실패: ${e.message}")
            ""
        }
    }

    private fun sendBadRequestResponse(writer: java.io.BufferedWriter, message: String = "Bad Request") {
        try {
            writer.write("HTTP/1.1 400 Bad Request\r\n")
            writer.write("Content-Type: text/plain\r\n")
            writer.write("Content-Length: ${message.length}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(message)
            writer.flush()
        } catch (e: Exception) {
            logger.debug("Bad Request 응답 전송 실패: ${e.message}")
        }
    }

    private fun sendSuccessResponse(writer: java.io.BufferedWriter, response: String) {
        try {
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json; charset=UTF-8\r\n")
            writer.write("Content-Length: ${responseBytes.size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.write(response)
            writer.flush()
            logger.debug("응답 전송 완료: ${response.length}자")
        } catch (e: Exception) {
            logger.error("응답 전송 실패: ${e.message}")
        }
    }

    private fun handleMCPRequestFast(requestBody: String): String {
        return try {
            logger.debug("MCP 요청 수신: ${requestBody.take(100)}...")

            val mcpRequest = objectMapper.readValue(requestBody, MCPRequest::class.java)

            when (mcpRequest.method) {
                "tools/call" -> {
                    when (mcpRequest.params.name) {
                        "travel_planner" -> handleTravelPlanningFast(mcpRequest)
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

    private fun handleTravelPlanningFast(mcpRequest: MCPRequest): String {
        val arguments = mcpRequest.params.arguments
        val destination = arguments["destination"] as? String ?: "서울"
        val duration = (arguments["duration"] as? Number)?.toInt() ?: 3
        val dailyBudget = (arguments["daily_budget"] as? Number)?.toLong() ?: 100000L
        val intensity = arguments["intensity"] as? String ?: "medium"
        val preferences = arguments["preferences"] as? String ?: ""

        logger.info("여행 계획 생성: $destination, ${duration}일")

        // === 새로운 Places API 사용 ===
        val placesData = try {
            // MCP 요청을 TravelPlanRequest로 변환
            val travelRequest = TravelPlanRequest(
                destination = destination,
                duration = duration,
                dailyBudget = dailyBudget,
                intensity = intensity,
                centerLat = getCoordinatesForDestination(destination).first,
                centerLng = getCoordinatesForDestination(destination).second,
                radiusMeters = when(intensity) {
                    "low" -> 1500
                    "high" -> 3000
                    else -> 2000
                },
                includeTypes = listOf("restaurant", "tourist_attraction", "cafe", "museum"),
                minRating = 4.0,
                minUserRatings = 200,
                mealsPerDay = 3
            )
            
            val places = placesSearchService.searchFilteredPlaces(travelRequest)
            logger.info("실제 장소 데이터: ${places.size}개 발견")
            
            // 장소 데이터를 JSON 문자열로 변환
            buildRealPlacesJson(places, destination)
        } catch (e: Exception) {
            logger.warn("Places API 실패, 샘플 데이터 사용: ${e.message}")
            createSamplePlacesCandidatesJson(destination)
        }

        // Ollama 호출 - 실제 장소 데이터와 함께
        val travelPlan = generateTravelPlanWithOllamaFast(destination, duration, dailyBudget, intensity, preferences, placesData)

        return createSuccessResponse(mcpRequest.id, travelPlan)
    }

    private fun searchGooglePlacesLegacyAsCandidatesJson(
        destination: String,
        intensity: String,
        center: Pair<Double, Double>
    ): String {
        // 새로운 Places 서비스를 사용할 예정이지만, 일단 기존 코드 유지
        if (googlePlacesApiKey.isBlank()) {
            return createSamplePlacesCandidatesJson(destination)
        }

        val searchType = when (intensity.lowercase()) {
            "low" -> "restaurant"
            "high" -> "tourist_attraction"
            else -> "restaurant"
        }

        return try {
            val places = searchNearbyLegacy(
                apiKey = googlePlacesApiKey,
                centerLat = center.first,
                centerLng = center.second,
                radius = 1500,
                type = searchType
            )

            val filtered = places
                .filter { (it.rating ?: 0.0) >= 3.0 }
                .filter { (it.userRatings ?: 0) >= 10 }
                .sortedByDescending { it.rating ?: 0.0 }
                .take(8)

            logger.info("빠른 검색 결과: ${filtered.size}개")
            buildCandidatesJson(destination, filtered)
        } catch (e: Exception) {
            logger.warn("빠른 Places 검색 실패: ${e.message}")
            createSamplePlacesCandidatesJson(destination)
        }
    }

    private fun generateTravelPlanWithOllamaFast(
        destination: String,
        duration: Int,
        dailyBudget: Long,
        intensity: String,
        preferences: String,
        placesCandidatesJson: String
    ): String {

        val activitiesPerDay = when (intensity.lowercase()) {
            "high" -> "6-7개"
            "low" -> "3-4개"
            else -> "4-5개"
        }
        val totalBudget = dailyBudget * duration

        val prompt = """
        $destination $duration 일 여행 계획을 오직 JSON 한 개로 생성하세요.
        
        입력 candidates:
        $placesCandidatesJson
        
        [필수 규칙]
        - 하루 5개 활동 고정: 08:00(아침), 10:00(관광), 12:00(점심), 15:00(카페), 18:00(저녁)
        - 타입 매핑(반드시 준수): 08:00/12:00/18:00=RESTAURANT, 10:00=ATTRACTION, 15:00=CAFE
        - 활동의 name/lat/lng/address/type/rating은 입력 candidates의 **동일 후보**에서만 복사(임의 작성 금지)
        - rating < 3.5 제외, 같은 candidate는 하루 1회만 사용
        - 총 예산: ${totalBudget}원 (일일 ${dailyBudget}원), 거리/동선 고려
        
        [금지]
        - 주석/설명/문자열 밖 쉼표/생략부호(...) 금지
        - 배열/객체 끝 trailing comma 금지
        - 스키마 외 키 추가 금지
        
        [출력 스키마(키 고정)]
        {
          "destination": "$destination",
          "duration": $duration,
          "totalBudget": $totalBudget,
          "itinerary": [
            {
              "day": 1,
              "date": null,
              "activities": [
                {
                  "time": "08:00",
                  "type": "RESTAURANT",
                  "name": "...",
                  "description": "아침식사",
                  "location": { "lat": 0, "lng": 0, "address": "..." },
                  "duration": 60,
                  "cost": 0,
                  "rating": 0
                }
              ],
              "dailyCost": 0
            }
          ],
          "summary": {
            "totalCost": 0,
            "totalActivities": 15,
            "typeCount": { "RESTAURANT": 0, "ATTRACTION": 0, "CAFE": 0 },
            "averageRating": 0
          }
        }
        
        오직 위 스키마대로 day 1~$duration 까지 완전한 JSON **한 개**만 출력.
        """.trimIndent()

        // 1차 생성(스트림 컷)
        val first = callOllamaAPIFast(prompt)
            ?: throw RuntimeException("Ollama 응답 없음(1차)")
        val cleaned1 = cleanAndFixJson(first)
        logger.info("정리된 JSON 길이(1차): ${cleaned1.length}")

        // 검증 → 실패 시 수정 루프
        return try {
            JSONObject(cleaned1)
            logger.info("JSON 검증 성공(1차)")
            cleaned1
        } catch (e1: Exception) {
            logger.warn("JSON 검증 실패(1차) → 수정 루프: ${e1.message}")
            val repaired = fixJsonWithOllama(cleaned1) ?: throw RuntimeException("수정 실패")
            val cleaned2 = cleanAndFixJson(repaired)
            logger.info("정리된 JSON 길이(수정): ${cleaned2.length}")
            JSONObject(cleaned2) // 최종 검증
            logger.info("JSON 검증 성공(수정)")
            cleaned2
        }
    }

    private fun fixJsonWithOllama(broken: String): String? {
        val repairPrompt = """
            다음 문자열은 JSON 형식 오류가 있습니다. 스키마/키/값을 변경하지 말고,
            오직 유효한 JSON(최상위 객체 하나)으로 **수정된 JSON만** 출력하세요.
            주석/설명/…/trailing comma 금지.
            
            원문:
            $broken
            """.trimIndent()

        return callOllamaAPIFast(
            prompt = repairPrompt,
            // format:"json"은 "수정된 JSON" 자체를 문자열로 준다고 보기 어렵습니다.
            // 여기서는 format:null로 두고, 결과에서 첫 {..} 블록만 추출하세요.
        )
    }

    private fun cleanAndFixJson(raw: String): String {
        var s = raw.trim()
        s = s.replace("```json", "").replace("```", "").trim()
        s = s.replace(Regex("""\.\.\.+"""), "") // ellipsis 제거

        // wrapper 제거(혹시 format:null 호출분 대비)
        if (s.startsWith("{") && s.contains("\"response\"")) {
            runCatching {
                val obj = JSONObject(s)
                s = obj.optString("response", s)
            }
        }

        // 첫 '{'~마지막 '}' 범위만 취득
        val fb = s.indexOf('{')
        val lb = s.lastIndexOf('}')
        if (fb != -1 && lb > fb) s = s.substring(fb, lb + 1)

        // trailing commas
        s = s.replace(Regex(",\\s*([}\\]])"), "$1")

        // 괄호 균형 복구
        val open = s.count { it == '{' }
        val close = s.count { it == '}' }
        if (open > close) s += "}".repeat(open - close)

        return s
    }


    private fun callOllamaAPIFast(prompt: String): String? {
        logger.info("Ollama API 호출 시작...(stream)")

        val reqJson = JSONObject().apply {
            put("model", ollamaModel)
            put("prompt", prompt)
            put("stream", true)
            put("format", "json")
            put("keep_alive", "3m")
            put("options", JSONObject().apply {
                put("temperature", 0.0)
                put("top_p", 0.1)
                put("top_k", 40)
                put("repeat_penalty", 1.05)
                put("num_predict", 1100)          // ⬅ 토큰 상한 완만히 하향(출력 미니파이와 병행)
                put("stop", JSONArray().apply { put("```") })
            })
        }

        val request = Request.Builder()
            .url("$ollamaBaseUrl/api/generate")
            .post(reqJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            logger.info("Ollama 응답 코드: ${resp.code}")
            if (!resp.isSuccessful) {
                logger.error("Ollama HTTP 실패: ${resp.code} - ${resp.body?.string()}")
                return null
            }

            val source = resp.body?.source() ?: return null

            // --- 스트림 파서 상태 ---
            val startNs = System.nanoTime()
            val hardLimitSec = 90                 // ⬅ 하드 타임리밋(환경에 맞춰 조절)
            val agg = StringBuilder()             // NDJSON 라인 조립용 버퍼
            val out = StringBuilder()             // 최종 JSON 조립
            var started = false
            var depth = 0
            var inString = false
            var escape = false

            fun onPiece(piece: String): String? {
                // JSON 텍스트 조각을 받아 균형 괄호로 조기 종료
                for (ch in piece) {
                    if (!started) {
                        if (ch == '{') {
                            started = true
                            depth = 1
                            out.append(ch)
                        }
                        // '{' 전 문자는 무시
                    } else {
                        out.append(ch)
                        // 문자열 상태 추적(문자열 내부의 중괄호는 무시)
                        if (escape) {
                            escape = false
                        } else {
                            when (ch) {
                                '\\' -> escape = true
                                '"'  -> inString = !inString
                                '{'  -> if (!inString) depth++
                                '}'  -> if (!inString) {
                                    depth--
                                    if (depth == 0) return out.toString() // ✅ 완결
                                }
                            }
                        }
                    }
                }
                return null
            }

            // --- NDJSON 수신 루프(라인 비의존) ---
            val buf = okio.Buffer()
            while (true) {
                // 하드 타임리밋 체크
                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                if (elapsedSec > hardLimitSec) {
                    val partial = out.toString()
                    logger.warn("스트림 하드 타임아웃(${hardLimitSec}s). partial=${partial.length}")
                    return if (partial.isNotBlank()) partial else null
                }

                val read = source.read(buf, 8192)
                if (read == -1L) {
                    // EOF
                    val partial = out.toString()
                    if (partial.isNotBlank()) {
                        logger.warn("EOF 도달. partial=${partial.length}")
                        return partial
                    }
                    val rest = agg.toString()
                    if (rest.isNotBlank()) {
                        // 남은 라인을 한 번 더 시도
                        runCatching { JSONObject(rest) }.getOrNull()?.let { obj ->
                            val piece = obj.optString("response", "")
                            val done = obj.optBoolean("done", false)
                            onPiece(piece)?.let { return it }
                            if (done) return out.toString().ifBlank { null }
                        }
                    }
                    return null
                }

                val chunk = buf.readUtf8()
                agg.append(chunk)

                // '\n' 기준으로 NDJSON 분할(개행 없을 수도 있음)
                while (true) {
                    val idx = agg.indexOf("\n")
                    if (idx < 0) break
                    val line = agg.substring(0, idx).trim()
                    agg.delete(0, idx + 1)

                    if (line.isEmpty()) continue
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val piece = obj.optString("response", "")
                    val done = obj.optBoolean("done", false)

                    onPiece(piece)?.let {
                        logger.info("Ollama 호출 성공(완결 JSON 감지): ${it.length}자")
                        return it
                    }
                    if (done) {
                        val partial = out.toString()
                        logger.info("done=true 수신. partial=${partial.length}")
                        return if (partial.isNotBlank()) partial else null
                    }
                }
            }
        }
    }


    private fun searchNearbyLegacy(
        apiKey: String,
        centerLat: Double,
        centerLng: Double,
        radius: Int,
        type: String
    ): List<PlaceLite> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("maps.googleapis.com")
            .addPathSegments("maps/api/place/nearbysearch/json")
            .addQueryParameter("location", "${centerLat},${centerLng}")
            .addQueryParameter("radius", radius.toString())
            .addQueryParameter("type", type)
            .addQueryParameter("key", apiKey)
            .addQueryParameter("language", "ko")
            .build()

        val request = Request.Builder().url(url).build()

        return placesHttp.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) return emptyList()

            val json = JSONObject(body)
            if (json.optString("status") != "OK") return emptyList()

            val results = json.optJSONArray("results") ?: return emptyList()
            val places = mutableListOf<PlaceLite>()

            for (i in 0 until minOf(results.length(), 10)) {
                val place = results.getJSONObject(i)

                val id = place.optString("place_id", "")
                val name = place.optString("name", "")
                val rating = if (place.has("rating")) place.optDouble("rating") else null
                val userRatings = if (place.has("user_ratings_total")) place.optInt("user_ratings_total") else null
                val priceLevel = if (place.has("price_level")) place.optInt("price_level") else null

                val geometry = place.optJSONObject("geometry")
                val location = geometry?.optJSONObject("location")
                val lat = location?.optDouble("lat") ?: 0.0
                val lng = location?.optDouble("lng") ?: 0.0

                val typesArray = place.optJSONArray("types")
                val types = mutableListOf<String>()
                if (typesArray != null) {
                    for (j in 0 until typesArray.length()) {
                        types.add(typesArray.optString(j))
                    }
                }

                val openNowFlag = place.optJSONObject("opening_hours")?.optBoolean("open_now")

                places.add(PlaceLite(id, name, lat, lng, rating, userRatings, priceLevel, types, openNowFlag))
            }

            places
        }
    }

    private fun resolveCenterFromDestination(destination: String): Pair<Double, Double> {
        return when (destination.lowercase().replace(" ", "")) {
            "서울", "seoul" -> 37.5665 to 126.9780
            "부산", "busan" -> 35.1796 to 129.0756
            "제주", "제주도", "jeju" -> 33.4996 to 126.5312
            else -> 37.5665 to 126.9780
        }
    }

    private fun createSamplePlacesCandidatesJson(destination: String): String {
        val sample = listOf(
            PlaceLite("sample1", "샘플 맛집", 37.5665, 126.9780, 4.2, 500, 2, listOf("restaurant"), true),
            PlaceLite("sample2", "샘플 카페", 37.5665, 126.9780, 4.0, 300, 2, listOf("cafe"), true)
        )
        return buildCandidatesJson(destination, sample)
    }

    private fun buildCandidatesJson(destination: String, places: List<PlaceLite>): String {
        val root = JSONObject()
        root.put("destination", destination)
        val arr = JSONArray()
        places.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("type", p.types.firstOrNull() ?: "restaurant")
                put("rating", p.rating ?: 4.0)
            })
        }
        root.put("candidates", arr)
        return root.toString()
    }

    private fun createSuccessResponse(id: String, content: String): String {
        val response = MCPResponse(
            id = id,
            result = MCPResult(
                content = listOf(MCPContent(type = "text", text = content)),
                isError = false
            )
        )
        return objectMapper.writeValueAsString(response)
    }

    private fun createErrorResponse(id: String, message: String): String {
        val response = MCPResponse(
            id = id,
            error = MCPError(code = -1, message = message)
        )
        return objectMapper.writeValueAsString(response)
    }

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