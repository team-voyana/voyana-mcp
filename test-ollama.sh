# Ollama 빠른 테스트 스크립트

# 1. Ollama 상태 확인
echo "=== Ollama 상태 확인 ==="
curl -s http://localhost:11434/api/version

echo -e "\n=== 설치된 모델 확인 ==="
curl -s http://localhost:11434/api/tags

echo -e "\n=== 간단한 테스트 ==="
curl -X POST http://localhost:11434/api/generate \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3.2",
    "prompt": "Hello",
    "stream": false,
    "options": {
      "num_predict": 10
    }
  }'
