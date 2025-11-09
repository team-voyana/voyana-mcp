# Voyana MCP

AI 기반 여행 계획 추천 서비스 (MCP + Gemini + Google Places API)

## 🎯 주요 기능

- Gemini AI를 활용한 맞춤형 여행 일정 생성
- Google Places API 기반 실제 장소 정보 연동
- MCP(Model Context Protocol) 통합
- 예산, 강도, 선호도 기반 최적화

## 🚀 빠른 시작

### 필수 요구사항

- JDK 17+
- Gradle 8.x
- Google Places API Key
- Gemini API Key

### 환경 설정

```yaml
# application.yml
google:
  places:
    api-key: your_google_places_api_key
    
gemini:
  api:
    key: your_gemini_api_key
    url: https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent
```

### 실행

```bash
./gradlew bootRun
```

서버 실행 후: http://localhost:8080

## 📡 API

### 여행 계획 생성

```http
POST /api/v2/travel/plan
Content-Type: application/json

{
  "destination": "서울",
  "days": 3,
  "budget": 500000,
  "intensity": "MODERATE",
  "preferences": ["CULTURE", "FOOD", "SHOPPING"]
}
```

**응답 예시:**

```json
{
  "days": [
    {
      "day": 1,
      "places": [
        {
          "name": "경복궁",
          "category": "CULTURE",
          "startTime": "09:00",
          "duration": 120,
          "estimatedCost": 50000,
          "location": {
            "latitude": 37.5796,
            "longitude": 126.9770,
            "address": "서울 종로구"
          }
        }
      ]
    }
  ],
  "totalCost": 450000
}
```

### Google Places 테스트

```http
GET /api/test/places/nearby?location=37.5665,126.9780&radius=1000&type=restaurant
```

## 🛠️ 기술 스택

- **Language**: Kotlin 1.9.25
- **Framework**: Spring Boot 3.5.4
- **AI**: Gemini 2.5 Flash
- **API**: Google Places API (New)
- **HTTP**: OkHttp, WebFlux
- **Build**: Gradle

## 📂 프로젝트 구조

```
src/main/kotlin/voyana/mcpprototype/
├── client/mcp/          # Gemini API 클라이언트
├── controller/v2/       # REST API
├── service/v2/          # 비즈니스 로직
│   ├── GooglePlacesService.kt
│   └── TravelPlanService.kt
└── McpPrototypeApplication.kt
```

## 🔧 주요 클래스

- `TravelPlanService`: 여행 계획 생성 핵심 로직
- `GooglePlacesService`: Google Places API 연동
- `GeminiApiClient`: Gemini AI 통신

## 📝 개발 노트

- Gemini를 활용한 JSON 기반 여행 계획 생성
- Google Places API (New)의 Nearby Search 사용
- 코루틴 기반 비동기 처리
- 위치 기반 장소 검색 및 필터링


**환경 변수에 API 키 설정**


```

**빌드 오류**
```bash
./gradlew clean build
```