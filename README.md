# Voyana MCP Travel Recommendation Prototype

MCP(Model Context Protocol)를 활용한 AI 기반 여행 추천 서비스 프로토타입

## 🏗️ 아키텍처

```
사용자 요청 → TravelController → TravelService → MCPClient → MCPServer → Ollama + Google Places API
```

## 🚀 실행 방법

### 1. 환경 설정
```bash
# Google Places API 키 설정 (선택사항)
export GOOGLE_PLACES_API_KEY=your_api_key_here

# Ollama 실행 (필수)
ollama serve
ollama pull llama3.2
```

### 2. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 3. 서비스 확인
- Spring Boot API: http://localhost:8080
- MCP Server: http://localhost:8081 (자동 시작)

## 📡 API 엔드포인트

### 여행 계획 생성
```http
POST http://localhost:8080/api/travel/plan
Content-Type: application/json

{
  "destination": "서울",
  "duration": 3,
  "dailyBudget": 150000,
  "intensity": "medium",
  "preferences": ["food", "culture", "shopping"]
}
```

### 건강 체크
```http
GET http://localhost:8080/api/travel/health
```

## 📝 응답 예시

```json
{
  "destination": "서울",
  "duration": 3,
  "totalBudget": 450000,
  "itinerary": [
    {
      "day": 1,
      "date": null,
      "activities": [
        {
          "time": "09:00",
          "type": "ATTRACTION",
          "name": "경복궁",
          "description": "조선왕조 대표 궁궐",
          "location": {
            "lat": 37.5796,
            "lng": 126.9770,
            "address": "서울 종로구"
          },
          "duration": 120,
          "cost": 50000,
          "rating": 4.5
        }
      ],
      "dailyCost": 150000
    }
  ],
  "summary": {
    "totalCost": 450000,
    "totalActivities": 9,
    "typeCount": {
      "ATTRACTION": 3,
      "RESTAURANT": 3,
      "SHOPPING": 3
    },
    "averageRating": 4.2
  }
}
```

## 🔧 프로젝트 구조

```
src/main/kotlin/voyana/mcpprototype/
├── controller/
│   ├── TravelController.kt              # REST API 엔드포인트
│   └── dto/                             # Request/Response DTO
├── service/
│   ├── TravelService.kt                 # 비즈니스 로직
│   └── TravelRecommendationMCPServer.kt # MCP 서버 구현
├── client/
│   └── mcp/
│       ├── MCPClient.kt                 # MCP 클라이언트
│       └── MCPMessage.kt                # MCP 메시지 DTO
└── McpPrototypeApplication.kt           # Spring Boot 메인 클래스
```

## 🔍 주요 컴포넌트

### TravelController
- REST API 엔드포인트 제공
- 요청 검증 및 응답 처리
- 에러 핸들링

### TravelService  
- 비즈니스 로직 처리
- 요청 검증 및 변환
- MCPClient 호출

### MCPClient
- MCP 프로토콜 구현
- HTTP 통신 처리
- 응답 변환 및 에러 처리
- Fallback 로직

### TravelRecommendationMCPServer
- MCP 서버 구현 (포트 8081)
- Google Places API 연동
- Ollama LLM 연동
- 여행 계획 생성 로직

## 🛠️ 기술 스택

- **Backend**: Spring Boot 3.5.4, Kotlin 1.9.25
- **AI/LLM**: Ollama (llama3.2)
- **External API**: Google Places API
- **Protocol**: MCP (Model Context Protocol)
- **HTTP Client**: OkHttp
- **Build Tool**: Gradle

## 📋 테스트 시나리오

### 성공 케이스
1. 유효한 여행 계획 요청
2. Google Places API 연동 (API 키 있는 경우)
3. Ollama LLM 응답 처리

### Fallback 케이스
1. Google Places API 키 없음 → 샘플 데이터 사용
2. Ollama 응답 실패 → 기본 여행 계획 생성
3. MCP 서버 오류 → 클라이언트 레벨 Fallback

## 🐛 트러블슈팅

### MCP 서버 시작 실패
- 포트 8081이 사용 중인지 확인
- 로그에서 구체적인 오류 메시지 확인

### Ollama 연결 실패
```bash
# Ollama 상태 확인
ollama list
curl http://localhost:11434/api/version
```

### Google Places API 오류
- API 키 유효성 확인
- API 할당량 확인
- 빌링 계정 활성화 확인

## 📈 향후 개선 사항

1. **데이터베이스 연동**: 여행 계획 저장/조회
2. **인증/인가**: 사용자 관리 시스템
3. **캐싱**: Redis를 활용한 응답 캐싱
4. **모니터링**: 메트릭 및 로깅 개선
5. **UI**: React 프론트엔드 개발
6. **배포**: Docker 컨테이너화