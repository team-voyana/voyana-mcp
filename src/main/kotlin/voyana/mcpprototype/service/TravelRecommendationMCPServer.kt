package voyana.mcpprototype.service

import okhttp3.OkHttpClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.springframework.stereotype.Service

import java.io.IOException

@Service
class TravelRecommendationMCPServer {

    private val httpClient = OkHttpClient()
    private val googlePlacesApiKey = System.getenv("GOOGLE_PLACES_API_KEY")
        ?: ""
    private val ollamaBaseUrl = "http://localhost:11434"

    fun startServer() {
        println("MCP 서버가 시작되었습니다...")

        // 간단한 구현 - 실제 MCP 프로토콜 대신 직접 호출
        while (true) {
            print("\n명령어를 입력하세요 (search/recommend/quit): ")
            val command = readLine() ?: continue

            when (command.lowercase()) {
                "search" -> {
                    print("위치를 입력하세요: ")
                    val location = readLine() ?: continue
                    val result = searchGooglePlaces(location, "5000", "tourist_attraction")
                    println("검색 결과:\n$result")
                }
                "recommend" -> {
                    print("장소 데이터를 입력하세요: ")
                    val placesData = readLine() ?: continue
                    print("사용자 선호도를 입력하세요: ")
                    val preferences = readLine() ?: continue
                    val result = getOllamaRecommendation(placesData, preferences)
                    println("추천 결과:\n$result")
                }
                "quit" -> {
                    println("서버를 종료합니다.")
                    break
                }
                else -> println("알 수 없는 명령어입니다.")
            }
        }
    }

    private fun searchGooglePlaces(location: String, radius: String, type: String): String {
        val encodedLocation = java.net.URLEncoder.encode(location, "UTF-8")
        val url = "https://maps.googleapis.com/maps/api/place/textsearch/json" +
                "?query=$encodedLocation&radius=$radius&type=$type&key=$googlePlacesApiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBody ?: "{}")
                val results = jsonResponse.optJSONArray("results")

                if (results != null && results.length() > 0) {
                    val places = mutableListOf<String>()
                    for (i in 0 until minOf(5, results.length())) {
                        val place = results.getJSONObject(i)
                        val name = place.optString("name", "이름 없음")
                        val rating = place.optDouble("rating", 0.0)
                        val address = place.optString("formatted_address", "주소 없음")
                        places.add("$name (평점: $rating) - $address")
                    }
                    places.joinToString("\n")
                } else {
                    "검색 결과가 없습니다."
                }
            } else {
                "API 호출 실패: ${response.code} - ${response.message}"
            }
        } catch (e: IOException) {
            "Google Places API 호출 중 오류가 발생했습니다: ${e.message}"
        }
    }

    private fun getOllamaRecommendation(placesData: String, userPreferences: String): String {
        val prompt = """
            다음 장소 정보와 사용자 선호도를 바탕으로 개인화된 여행 추천을 제공해주세요:
            
            장소 정보: $placesData
            사용자 선호도: $userPreferences
            
            추천 형식:
            1. 추천 장소 (이유 포함)
            2. 방문 순서 제안
            3. 예상 소요 시간
            4. 특별 팁
            
            한국어로 답변해주세요.
        """.trimIndent()

        val json = JSONObject().apply {
            put("model", "llama3.2")
            put("prompt", prompt)
            put("stream", false)
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$ollamaBaseUrl/api/generate")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBody ?: "{}")
                jsonResponse.optString("response", "추천을 생성할 수 없습니다.")
            } else {
                "Ollama API 호출 실패: ${response.code} - ${response.message}"
            }
        } catch (e: Exception) {
            "Ollama API 호출 중 오류가 발생했습니다: ${e.message}"
        }
    }

    fun testGoogleAPI() {
        println("=== Google Places API 간단 테스트 ===")

        // 1. 매우 간단한 요청
        val testUrl = "https://maps.googleapis.com/maps/api/place/textsearch/json?query=Seoul&key=$googlePlacesApiKey"

        val request = Request.Builder()
            .url(testUrl)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            println("테스트 응답 코드: ${response.code}")
            println("테스트 응답 일부: ${body?.take(500)}")

            if (body != null) {
                val json = JSONObject(body)
                println("API 상태: ${json.optString("status")}")
                println("결과 개수: ${json.optJSONArray("results")?.length() ?: 0}")
                if (json.has("error_message")) {
                    println("오류 메시지: ${json.getString("error_message")}")
                }
            }
        } catch (e: Exception) {
            println("테스트 실패: ${e.message}")
            e.printStackTrace()
        }
    }
}

fun main() {
    println("여행 추천 MCP 서버를 시작합니다...")

    TravelRecommendationMCPServer().testGoogleAPI()

    TravelRecommendationMCPServer().startServer()
}