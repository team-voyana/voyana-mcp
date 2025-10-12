package voyana.mcpprototype.client.mcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody

@Component
class GeminiApiClient(
    @Value("\${google.api.key}") private val apiKey: String
) {

    private val webClient = WebClient.builder()
        .baseUrl("https://generativelanguage.googleapis.com")
        .build()

    suspend fun generateTravelPlan(prompt: String): String {
        val request = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.1,
                "maxOutputTokens" to 8192,
                "responseMimeType" to "application/json"
            )
        )

        return try {
            val response = webClient.post()
                // 모델명 수정
                .uri("/v1beta/models/gemini-2.5-flash:generateContent")
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .bodyValue(request)
                .retrieve()
                .awaitBody<Map<String, Any>>()


            println(response)

            val candidates = response["candidates"] as List<Map<String, Any>>
            val content = candidates[0]["content"] as Map<String, Any>
            val parts = content["parts"] as List<Map<String, Any>>

            val text = parts[0]["text"] as String

            println("=== Gemini 응답 ===")
            println(text)
            println("==================")

            text
        } catch (e: WebClientResponseException) {
            throw RuntimeException(
                "Gemini API 호출 실패 (${e.statusCode}): ${e.responseBodyAsString}",
                e
            )
        } catch (e: Exception) {
            throw RuntimeException("Gemini API 처리 중 오류: ${e.message}", e)
        }
    }
}