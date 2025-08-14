package voyana.mcpprototype.service

import okhttp3.OkHttpClient
import org.springframework.stereotype.Service
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse

@Service
class RecommendService {
    private val httpClient = OkHttpClient()
    private val googlePlacesApiKey = System.getenv("GOOGLE_PLACES_API_KEY")
        ?: ""
    private val ollamaBaseUrl = "http://localhost:11434"


    fun recommend(request: TravelPlanRequest): TravelPlanResponse? {

        searchGooglePlaces(request);

        return null;
    }

    private fun searchGooglePlaces(request: TravelPlanRequest): TravelPlanRequest {


    }

    private fun getOllamaRecommendation() {

    }

}