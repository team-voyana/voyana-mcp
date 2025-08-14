package voyana.mcpprototype.controller

import lombok.RequiredArgsConstructor
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import voyana.mcpprototype.controller.dto.TravelPlanResponse
import voyana.mcpprototype.service.TravelRecommendationMCPServer
import javax.validation.Valid

@RequiredArgsConstructor
@RestController
class SampleController(
    val travelRecommendationMCPServer: TravelRecommendationMCPServer
) {

    @PostMapping("/api/v1/plans")
    fun plan(@Valid @RequestBody request: TravelPlanRequest): TravelPlanResponse? {

        travelRecommendationMCPServer.startServer();

        return null;
    }

}