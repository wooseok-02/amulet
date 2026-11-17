# 마이페이지 실서버 통합 테스트

2026-11-17 (Asia/Seoul), 서버 API 반영 후 실행.

## 결론

배포 APK 0.2.1과 실제 서버를 연결한 핵심 통합 시나리오가 통과했다. 앱 코드 수정 없이 양력·음력 평달·음력 윤달 계정 3개에서 로그인, 회원정보 조회·표시, 로그아웃, 로컬 세션 제거와 서버 토큰 무효화를 확인했다.

## 실행 환경

- 서버: `https://amulet-api.onrender.com`. 공개 OpenAPI에 `/api/v1/me`, `/api/v1/auth/logout` 추가 확인.
- 대상 앱: `com.example.bukku`, versionName 0.2.1, versionCode 3. 앞서 서명·업데이트 설치를 검증한 release APK를 그대로 사용.
- 기기: `Bukku_Install_20261117`, Android API 36 / x86_64 에뮬레이터.
- 테스트: `MyPageLiveIntegrationTest#deployedProfilesAndRealAppLoginLogout`.
- AndroidTest APK를 배포 인증서로 재서명하여 실제 release 앱에 연결. 모의 `ProfileApi` 또는 모의 HTTP 서버를 사용하지 않았다.
- 결과: AndroidJUnitRunner `OK (1 test)`, 92.645초. 결과 JSON에는 계정 준비를 포함한 확인 23건과 미실행 2건이 기록됐다. 23개의 독립 JUnit 테스트를 실행했다는 뜻은 아니다.

## 통과 항목

| 항목 | 실제 확인 |
| --- | --- |
| 인증 없는 요청 | 사전 HTTP 확인에서 GET me와 POST logout 모두 401 |
| 잘못된 토큰 | 형식이 잘못된 토큰과 실제 발급 JWT의 서명을 변조한 토큰 모두 me·logout에서 401 |
| 정보 계약 | 계정 3개 모두 me 200, Content-Type application/json, 필수 필드·타입·값 일치 |
| 양력 | SOLAR / false, birthTime null → ‘양력’, ‘모름’, 여성 |
| 음력 평달 | LUNAR / false, 08:10 → ‘음력 · 평달’, 남성 |
| 음력 윤달 | LUNAR / true, 13:30 → ‘음력 · 윤달’, 여성 |
| 계정 격리 | A·B 정보가 서로 다르고 각 계정의 모든 필드가 가입한 값과 일치 |
| 실제 앱 로그인 | 세 계정 모두 실제 `/auth/login`을 사용하는 AppModel 인증 흐름으로 로그인. 미리보기 비활성 확인 |
| 실제 화면 조회 | 마이페이지 진입 시 실제 서버에서 가져온 ProfileState와 이름·이메일·기본 정보 네 칸의 렌더링 일치 |
| 로그아웃 | 세 계정 모두 화면의 ‘로그아웃’을 클릭하여 처리. 클라이언트가 빈 204만 인정하는 성공 경로 완료 및 오류 문구 없음 확인 |
| 로컬 상태 제거 | 암호화 SessionStore, profileState, fortune 및 보관함 상태 제거, 로그인 화면 전환과 빈 이메일 입력 확인 |
| 서버 무효화 | 앱 로그아웃 직전 사용한 동일 access token으로 me를 즉시 재조회하여 세 계정 모두 401 |
| 다른 계정 유지 | A 로그아웃 뒤 B의 기존 토큰 조회 성공, B 로그아웃 뒤 C의 기존 토큰 조회 성공 |

로그인 입력은 테스트가 AppModel의 폼 상태를 채우고 실제 인증 동작을 실행했다. 조회는 실제 앱 화면 진입으로, 로그아웃은 화면 버튼 클릭으로 실행했다. 회원가입은 테스트 준비용 실서버 API 호출이다. 모든 입력을 손가락/키보드 터치로 수행한 전면 수동 E2E나 갤럭시 S26 실기기 검증으로 표시하지 않는다.

## 캡처 및 데이터

`결과/마이페이지_통합테스트_2026-11-17/`에 결과 JSON, instrumentation 로그, 테스트 빌드 로그, 각 계정의 마이페이지 상단·기본 정보·로그아웃 후 화면 등 9개 PNG를 보관했다. 세 달력의 기본 정보와 로그인 복귀 화면을 육안 확인했다.

테스트용 계정 3개를 생성했다. 사용자·가족의 실제 계정은 사용하지 않았다. 현재 API 계약에 계정 삭제가 없어 테스트 계정을 임의 삭제하지 않았다. 비밀번호·access token·refresh token은 보고서와 결과 파일에 기록하지 않는다. 기기 세션은 로그아웃으로 제거했고 기존 저장 상태는 시험의 finally에서 복원한다.

## 남은 별도 항목

- **정상 서명된 실제 시간 만료 토큰**: 해당 fixture가 없고 새 토큰의 만료를 기다리지 않았으므로 미실행. 변조·무효·로그아웃으로 폐기된 토큰의 401과 구분한다.
- **실서버 5xx 장애 주입**: 운영 서버에 고의 장애를 발생시키지 않았다. 실패 시 오류 유지·세션 보존·재시도는 기존 로컬 HTTP/상태/기기 테스트로 확인한 범위다.
- 기존의 갤럭시 S26 설치 실패 원인, APK 링크를 통한 실기기 다운로드/설치는 이번 마이페이지 통합 시험과 별개다.

앱 구현과 배포 APK를 바꿀 필요는 발견하지 않았다. 새 테스트 코드와 결과 문서만 추가·갱신했다.

## 재실행

`MyPageLiveIntegrationTest`는 기본 실행에서는 건너뛴다. 실제 서버에 테스트 계정을 생성하는 실행에만 명시적으로 다음 인자를 준다.

```text
adb -s emulator-5556 shell am instrument -w -r \
  -e class com.example.bukku.MyPageLiveIntegrationTest \
  -e bukkuLiveMyPage true \
  com.example.bukku.test/androidx.test.runner.AndroidJUnitRunner
```

대상 release 앱과 AndroidTest APK의 서명 인증서가 같아야 한다. 서버의 세션 원문을 출력하거나 테스트 결과에 저장하지 않는다.
