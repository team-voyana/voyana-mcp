#!/bin/bash

echo "🧹 Gradle 캐시 및 빌드 파일 정리..."
./gradlew clean

echo "🔧 의존성 다시 다운로드..."
./gradlew build --refresh-dependencies

echo "🚀 애플리케이션 실행..."
./gradlew bootRun