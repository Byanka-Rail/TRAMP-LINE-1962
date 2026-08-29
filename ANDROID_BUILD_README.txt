TRAMP LINE 1962 Android v2.2

1) 이 패키지의 android/ 폴더와 .github/workflows/build-apk.yml 을
   TRAMP-LINE-1962 저장소 루트에 올립니다.
2) GitHub -> Actions -> Build TRAMP LINE APK -> Run workflow.
3) 빌드 완료 후 Artifacts의 TRAMP_LINE_1962_ANDROID_v2.2 를 내려받습니다.
4) 내부 app-debug.apk 를 Android 기기에 설치합니다.

콘텐츠 업데이트:
- 기본 내장 콘텐츠: v3.6.10.1
- 앱 실행 때 GitHub Latest Release 확인
- TRAMP_LINE_UPDATE_v<버전>.zip asset을 찾음
- ZIP 내부 index.html의 TRAMP_LINE_CONTENT_VERSION을 검사
- 더 최신이면 앱 내부 index.html을 원자적으로 교체
- 네트워크 실패/ZIP 오류는 게임 실행을 막지 않음
- 이전 index.html은 index.prev.html로 1세대 보존

주의:
- 포함된 keystore는 개인 사이드로드/CI 연속설치용이다.
- Play Store 배포용 서명키로 사용하지 말 것.
