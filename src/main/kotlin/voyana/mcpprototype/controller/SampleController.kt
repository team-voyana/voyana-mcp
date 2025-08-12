package voyana.mcpprototype.controller

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import voyana.mcpprototype.controller.dto.TravelPlanRequest
import javax.validation.Valid

@RestController
class SampleController {

    @PostMapping("/api/v1/plans")
    fun plan(@Valid @RequestBody request: TravelPlanRequest): String {
        return "hello";
    }

}