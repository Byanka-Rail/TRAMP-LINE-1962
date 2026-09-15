TRAMP LINE 1962 Android v2.2

1) 이 패키지의 android/ 폴더와 .github/workflows/build-apk.yml 을
   TRAMP-LINE-1962 저장소 루트에 올립니다.
2) GitHub -> Actions -> Build TRAMP LINE APK -> Run workflow.
3) 빌드 완료 후 Artifacts의 TRAMP_LINE_1962_ANDROID_v2.2 를 내려받습니다.
4) 내부 app-debug.apk 를 Android 기기에 설치합니다.

릴리스로 콘텐츠만 갱신하기 (APK 재설치 없이):
 a) 태그를 콘텐츠 판번호와 같게 만든다  예) v3.8.0
 b) 에셋 이름을 TRAMP_LINE_UPDATE_v<태그>.zip 로 한다
 c) zip 최상위에 index.html 하나만 넣는다 (폴더 안에 넣으면 앱이 못 찾는다)
 d) zip 안 index.html 의 TRAMP_LINE_CONTENT_VERSION 이 태그보다 낮으면 무시된다

콘텐츠 업데이트:
- 기본 내장 콘텐츠: v3.8.0 (국면 중심 화면 · 부두 적부/항차/하역 · 항해당직 · 입출항 조선)
- 앱 실행 때 GitHub Latest Release 확인
- TRAMP_LINE_UPDATE_v<버전>.zip asset을 찾음
- ZIP 내부 index.html의 TRAMP_LINE_CONTENT_VERSION을 검사
- 더 최신이면 앱 내부 index.html을 원자적으로 교체
- 네트워크 실패/ZIP 오류는 게임 실행을 막지 않음
- 이전 index.html은 index.prev.html로 1세대 보존

주의:
- 포함된 keystore는 개인 사이드로드/CI 연속설치용이다.
- Play Store 배포용 서명키로 사용하지 말 것.
