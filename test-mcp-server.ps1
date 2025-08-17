# MCP 서버 테스트 스크립트 (PowerShell)
Write-Host "MCP 서버 테스트 스크립트" -ForegroundColor Cyan
Write-Host "========================" -ForegroundColor Cyan

# 1. MCP 서버 상태 확인 (포트 8081)
Write-Host "`n1. MCP 서버 상태 확인 (포트 8081)..." -ForegroundColor Yellow
$mcp = Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue
if ($mcp) {
    Write-Host "✅ MCP 서버가 실행 중입니다." -ForegroundColor Green
} else {
    Write-Host "❌ MCP 서버가 실행되지 않았습니다." -ForegroundColor Red
}

# 2. Spring Boot 서버 상태 확인 (포트 8080)
Write-Host "`n2. Spring Boot 서버 상태 확인 (포트 8080)..." -ForegroundColor Yellow
$spring = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
if ($spring) {
    Write-Host "✅ Spring Boot 서버가 실행 중입니다." -ForegroundColor Green
} else {
    Write-Host "❌ Spring Boot 서버가 실행되지 않았습니다." -ForegroundColor Red
}

# 3. Ollama 서버 상태 확인
Write-Host "`n3. Ollama 서버 상태 확인..." -ForegroundColor Yellow
try {
    $ollamaResponse = Invoke-RestMethod -Uri "http://localhost:11434/api/tags" -Method Get -TimeoutSec 5
    Write-Host "✅ Ollama 서버가 실행 중입니다." -ForegroundColor Green
    
    # llama3.2 모델 확인
    $models = & ollama list 2>$null
    if ($models -match "llama3.2") {
        Write-Host "✅ llama3.2 모델이 설치되어 있습니다." -ForegroundColor Green
    } else {
        Write-Host "⚠️  llama3.2 모델이 없습니다. 설치가 필요합니다:" -ForegroundColor Yellow
        Write-Host "   ollama pull llama3.2" -ForegroundColor Cyan
    }
} catch {
    Write-Host "❌ Ollama 서버가 실행되지 않았습니다." -ForegroundColor Red
    Write-Host "   Ollama를 시작하려면: ollama serve" -ForegroundColor Cyan
}

# 4. API 테스트
Write-Host "`n4. API 엔드포인트 테스트..." -ForegroundColor Yellow
Write-Host "테스트 요청 전송 중..." -ForegroundColor Cyan

$body = @{
    destination = "서울"
    duration = 3
    dailyBudget = 100000
    intensity = "medium"
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/travel/plan" `
        -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Body $body `
        -TimeoutSec 30
    
    Write-Host "✅ API 응답 수신 성공" -ForegroundColor Green
    Write-Host "응답 일부:" -ForegroundColor Cyan
    $responseJson = $response | ConvertTo-Json -Depth 3
    Write-Host ($responseJson.Substring(0, [Math]::Min(500, $responseJson.Length)) + "...")
} catch {
    Write-Host "❌ API 응답 실패: $_" -ForegroundColor Red
}

Write-Host "`n========================" -ForegroundColor Cyan
Write-Host "테스트 완료" -ForegroundColor Green