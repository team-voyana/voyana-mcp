#!/bin/bash

echo "MCP 서버 테스트 스크립트"
echo "========================"

# MCP 서버 상태 확인
echo "1. MCP 서버 상태 확인 (포트 8081)..."
if lsof -i:8081 > /dev/null 2>&1; then
    echo "✅ MCP 서버가 실행 중입니다."
else
    echo "❌ MCP 서버가 실행되지 않았습니다."
fi

# Spring Boot 서버 상태 확인
echo ""
echo "2. Spring Boot 서버 상태 확인 (포트 8080)..."
if lsof -i:8080 > /dev/null 2>&1; then
    echo "✅ Spring Boot 서버가 실행 중입니다."
else
    echo "❌ Spring Boot 서버가 실행되지 않았습니다."
fi

# Ollama 서버 상태 확인
echo ""
echo "3. Ollama 서버 상태 확인..."
if curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "✅ Ollama 서버가 실행 중입니다."
    
    # llama3.2 모델 확인
    if ollama list | grep -q "llama3.2"; then
        echo "✅ llama3.2 모델이 설치되어 있습니다."
    else
        echo "⚠️  llama3.2 모델이 없습니다. 설치가 필요합니다:"
        echo "   ollama pull llama3.2"
    fi
else
    echo "❌ Ollama 서버가 실행되지 않았습니다."
    echo "   Ollama를 시작하려면: ollama serve"
fi

# API 테스트
echo ""
echo "4. API 엔드포인트 테스트..."
echo "테스트 요청 전송 중..."

# 간단한 테스트 요청
response=$(curl -s -X POST http://localhost:8080/api/travel/plan \
  -H "Content-Type: application/json" \
  -d '{
    "destination": "서울",
    "duration": 3,
    "dailyBudget": 100000,
    "intensity": "medium"
  }' \
  --max-time 30)

if [ $? -eq 0 ]; then
    echo "✅ API 응답 수신 성공"
    echo "응답 일부:"
    echo "$response" | head -c 500
    echo "..."
else
    echo "❌ API 응답 실패 (타임아웃 또는 연결 오류)"
fi

echo ""
echo "========================"
echo "테스트 완료"