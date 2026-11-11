# 부꾸 Android

Android Studio에서 이 `android` 폴더를 연다. 저장소 전체 문서와 백엔드 코드는 상위 폴더에 있다.

## 개발 기준

- Kotlin 2.4.20 / Jetpack Compose
- AGP 9.2.0 / Gradle 9.4.1 / JDK 17
- Compile / Target SDK 36, Minimum SDK 26
- `com.example.bukku`를 이번 0.2.0 배포 APK에서도 유지한다.

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
- 친구 운세와 AI 응원 API 연결, 친구 정보/상황 입력, 부적 꾸미기와 완성 화면.
- 문양/테두리 변경, PNG multipart 서버 저장, 서버 월별·날짜별 보관함 및 원격 이미지 상세. 디자인 미리보기만 기기 저장을 사용한다.
- 월별 캘린더, 날짜별 팝업과 빈 날짜 안내, 부적의 3D 뒤집기 및 당시 기록 확인.
- 월간 해석과 행운일은 API가 없어 예시로 표시한다. 알림은 기존 안내를 제공한다.

## 서버 설정과 미리보기

배포 문서의 `https://amulet-api.onrender.com`으로 자동 연결한다. 사용자용 서버 설정 화면은 없으며, 이전 버전에 저장된 사용자 지정 주소도 사용하지 않는다.
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
이번 UI 검증은 디자인 미리보기로 수행한다. 실제 서버 통합 테스트 및 실기기 검증 결과와 구분한다.

## 2026-11-11 저장·공유·배포

`docs/1111_api.md`가 최신 연결 계약이다. 내 부적은 서버에 저장하고, 친구 부적만 공유 URL을 발급한다. 친구용 부적은 보낸 사람 보관함에 저장하지 않는다.
해석과 행동 조언 원문은 각각 전달한다. 월간 요약은 별도 미정 API이며 부적 점수로 계산하지 않는다.
공유 생성·수령·목록·상세에는 기존 JWT/갱신 처리를 재사용한다. 시간 초과된 생성 요청은 자동으로 재전송하지 않는다.

Kakao 공유용 의존성 `com.kakao.sdk:v2-share:2.26.0`을 추가했다. 기존 Kotlin/AGP/Gradle/SDK 버전은 유지했다.
확정된 값이 제공되면 Git에서 제외된 `local.properties`에 `bukku.shareHost`, `bukku.kakaoNativeKey`를 설정하여 재빌드한다.
현재 기본 수신 호스트는 API 서버 도메인이며 최종 공유 웹 도메인 확정/assetlinks 검증을 의미하지 않는다.
네이티브 앱 키가 비어 있으면 카카오 전송 버튼은 비활성화한다. 유효 공유 URL을 받은 경우에만 링크 복사/재공유를 제공한다.
Manifest는 HTTPS `/share/` 수신과 설정된 카카오 커스텀 스킴을 처리한다. 미로그인 링크는 암호화해 보류하며 원래 링크 재방문을 안내한다.

서명 release 빌드에는 `BUKKU_SIGNING_PROPERTIES` 환경변수로 저장소 밖의 signing properties 경로를 지정한다.
해당 properties는 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 포함하며 Git/배포 전달 묶음에 넣지 않는다.
이 컴퓨터의 서명 자료는 `C:/chg/bukku-signing`에 보관한다. 이후 업데이트는 동일한 release 키를 사용한다.
배포 APK와 인증서 지문, 확인/미확인 항목은 `docs/APP_DISTRIBUTION_HANDOFF.md`에 기록한다.
기존 debug 서명 APK와 release 서명은 다르므로 덮어 설치할 수 없다. 기존 기기 데이터 확인 없이 앱을 삭제하지 않는다.

설치 안내 웹 소스는 `web/install/`이며 별도 공유 도메인 호스팅과 CORS/프록시 설정이 필요하다.
웹 로직 시험: `node web/tests/install.test.cjs` (저장소 루트 기준).
Android 실서버 시험은 `StorageIntegrationTest#liveStorageAndSharingContract`에 `-e bukkuLive true`를 준 경우에만 실행한다.
실서버 시험은 테스트 계정/부적을 생성한다. 계정 삭제 API는 이번 범위에 없으며 테스트 데이터를 자동 삭제하지 않는다. 비밀번호와 토큰은 보고서에 기록하지 않는다.

관련 요구사항: 상위 `docs/AUTH_INTEGRATION.md`, `docs/SCR_3.md`, `docs/SCR_1.md`, `docs/SCR_4.md`, `docs/FIGMA_UI_IMPLEMENTATION.md`.
폰트는 Noto Serif KR이며 라이선스는 `FONT-LICENSE.txt`에 있다.
로고는 사용자가 제공한 캡처의 로고 영역을 화면에서 표시한다. 원본 로고와 정확한 Figma 폰트 파일을 받으면 교체할 수 있다.

`local.properties`, `.gradle`, `.idea`, 빌드 출력은 Git에서 제외한다.
Windows의 현재 경로에 한글이 포함되어 있어 `android.overridePathCheck=true`를 설정했다.
