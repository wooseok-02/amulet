# 부꾸 Android

Android Studio에서 이 `android` 폴더를 연다. 저장소 전체 문서와 백엔드 코드는 상위 폴더에 있다.

## 개발 기준

- Kotlin 2.4.20 / Jetpack Compose
- AGP 9.2.0 / Gradle 9.4.1 / JDK 17
- Compile / Target SDK 36, Minimum SDK 26
- `com.example.bukku`는 개발용 임시 패키지 이름이다. 출시 전 팀에서 확정한다.

## 실행

1. Android Studio에서 이 폴더를 열고 Gradle Sync를 기다린다.
2. Gradle JDK는 JDK 17을 선택한다. 이 컴퓨터에는 `.gradle/config.properties`에 로컬 JDK 경로가 설정되어 있다.
3. 에뮬레이터 또는 Android 기기를 선택하고 Run을 누른다.

명령줄에서는 JAVA_HOME을 JDK 17 경로로 설정한 뒤 `./gradlew.bat assembleDebug`를 실행한다.

## 구현한 기능

- 제공된 Figma 캡처를 기준으로 크림 배경, 노란 버튼, 붉은 강조선, 갈색 테두리, 점무늬와 한글 명조체를 적용했다.
- 로그인, 회원가입(음력 윤달 선택, 날짜/시간 선택), 입력 검증, 중복 제출 방지.
- `POST /auth/signup`, `/auth/login`, `/auth/refresh` 연결. 세션은 Android Keystore AES-GCM으로 암호화한다. 비밀번호는 저장하지 않는다.
- 앱 재실행 시 토큰을 갱신하고 인증 거절이면 로그인 화면으로 이동한다. 통신 오류는 토큰을 유지하고 재시도를 제공한다.
- `GET /fortune/today`, `POST /fortune/interpret` 연결 및 200자 고민 입력, 7개 상황, 4개 마음 상태.
- API가 없는 보관함과 알림은 예시이며 친구 선물과 부적 편집기는 후속 구현 안내를 제공한다.

## 서버 설정과 미리보기

현재 GitHub 문서에는 실제 배포 URL이 없다. 내 정보 화면 하단 **서버 설정**에서 HTTPS 서버 주소를 입력한다.
인증과 운세는 실제 API에 요청하며, 실패를 더미 성공으로 바꾸지 않는다.
서버가 없어도 **디자인 미리보기**로 예시 운세와 AI 해석 화면을 볼 수 있다. 이 모드는 로그인 상태를 만들거나 저장하지 않는다.
로그인 성공 후에는 실제 서버 데이터만 표시한다.
앱은 Supabase/LLM에 직접 접속하지 않는다. 토큰 만료 정책은 서버가 결정한다.

## 검증

`./gradlew.bat testDebugUnitTest`는 회원가입 요구사항의 경계값을 검증한다.
에뮬레이터의 `SessionStoreTest`는 암호화 저장, 토큰 교체 및 삭제, 손상된 저장값 정리를 검증한다. 테스트 실행 결과는 3개 통과다.
현재 테스트 기기는 `Bukku_Test`이며 Android Studio 실행 기기로 선택할 수 있다. 기기 파일은 `C:/chg/bukku-avd`에 있다.
현재 컴퓨터에서는 한글 사용자 경로에 대한 Gradle 테스트 실행 오류를 피하려고 `-g C:/chg/bukku-gradle-cache`를 사용한다.
OneDrive의 생성 파일 잠금을 피하기 위해 `local.properties`에 `build.root=C:/chg/bukku-build`를 설정했다.
이 설정은 이 컴퓨터 전용이며 Git에서 제외된다. 현재 APK 경로는 `C:/chg/bukku-build/app/outputs/apk/debug/app-debug.apk`다.
실제 서버에 계정/프로필이 저장되는지, 운영 토큰 갱신과 실기기 테스트는 서버 주소 제공 이후에 확인해야 한다.

관련 요구사항: 상위 `docs/AUTH_INTEGRATION.md`, `docs/SCR_3.md`, `docs/SCR_1.md`.
폰트는 Noto Serif KR이며 라이선스는 `FONT-LICENSE.txt`에 있다.
로고는 사용자가 제공한 캡처의 로고 영역을 화면에서 표시한다. 원본 로고와 정확한 Figma 폰트 파일을 받으면 교체할 수 있다.

`local.properties`, `.gradle`, `.idea`, 빌드 출력은 Git에서 제외한다.
Windows의 현재 경로에 한글이 포함되어 있어 `android.overridePathCheck=true`를 설정했다.
