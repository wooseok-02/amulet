# 부꾸 0.2.0 배포 전달 및 검증 결과

확인일: 2026-11-11 (Asia/Seoul). 기준 문서: `docs/1111_api.md` / 백엔드 전달본 `0a175a3`.

## 전달 파일과 앱 식별 정보

| 항목 | 값 |
| --- | --- |
| 서명 배포 APK | `bukku-0.2.0-release.apk` — 로컬 `결과/배포_2026-11-11/` 및 전달 ZIP |
| 실제 패키지명 | `com.example.bukku` — 기존 값을 유지 |
| 버전 | `0.2.0` / versionCode `2` |
| Debuggable | false — release APK의 aapt badging 확인 |
| APK 서명 | apksigner 검증 통과, APK Signature Scheme v2 |
| 서명 인증서 SHA-256 | `6C:81:15:36:22:07:B4:FF:1D:73:AD:B8:18:9C:C2:A5:33:FA:EC:FF:8A:7E:03:80:17:FF:BE:DC:CA:26:65:F9` |
| APK 파일 SHA-256 | `25BE58746392ADB3C7073BC517DEABD4AD97252496E2648B7BF0661DA7753D20` |
| 카카오 Android 키 해시 | `VkoaWT+Y/4Eo/4Kwoaq/r2/HQ/k=` — 인증서 SHA-1의 Base64 |

인증서 SHA-256과 APK 파일 해시는 서로 다른 값이다. Android 도메인 연결에는 **인증서 SHA-256**을 사용한다.
개인 서명 키와 비밀번호는 전달 파일·Git에 포함하지 않았다. 이후 업데이트는 이 release 키를 유지해야 한다.
기존 debug 서명 앱과는 인증서가 다르므로 Android에서 덮어 설치할 수 없다. 기기 데이터 확인 없이 기존 앱을 삭제하지 않는다.
기존 기기 저장 부적의 자동 서버 마이그레이션은 이번 범위가 아니다.

## 구현

- 내 부적: 1000×1400 PNG + 필수 원문 필드를 multipart로 업로드하고 서버 ID를 사용한다.
- 해석과 행동 조언을 별도로 유지하며, 내 부적 공유는 제공하지 않는다.
- 실제 보관함: 서버 월·날짜 목록과 상세, PNG 원격 표시, 앞뒷면과 당시 정보. 점수로 월간 운세를 계산하지 않는다.
- 친구 부적: 공유 생성 API 및 URL 유지/카카오 SDK 연결, 보낸 사람 보관함 저장 제외, 실패/503 안내.
- 수신: HTTPS `/share/` 및 설정된 카카오 스킴 처리, 새 실행/onNewIntent, 미로그인 토큰 암호화 보류, 로그인 후 재방문/수령, 서버 상세 앞면 표시.
- 생성 요청은 연결 실패/타임아웃에 무조건 자동 재전송하지 않는다. 결과 불명확 시 보관함 확인을 안내한다.
- 설치 안내 소스는 `web/install/`. 설치 JSON 조회, 준비 중/다운로드/실패 UI, 설치 후 원래 링크 재방문 안내를 포함한다.

## 실제 확인 결과

| 확인 | 결과와 범위 |
| --- | --- |
| 빌드 | assembleRelease, assembleDebug, assembleDebugAndroidTest, testDebugUnitTest 및 release lint 통과 |
| 단위 테스트 | 20개 통과. multipart·인증·201·PNG 크기·원문 보존·토큰 경로/호스트 검사 포함 |
| 기기 로컬 검사 | 7개 통과. 미리보기 전체 흐름, 부적 저장/앞뒷면, 세션 암호화/교체/손상 처리, 새 링크 실행/재생성/onNewIntent/미로그인 보류 |
| Android 실서버 시험 | 별도 live 테스트 1개 통과. 테스트 계정 2개/PNG 2건 저장, 월/날짜/빈 날짜, 과거 생성일, 원문/해석/행동 조언, 이미지 해시 일치, 타 계정 상세 404 |
| 실제 서버 UI | 세션 복원 후 서버 보관함/날짜/상세/PNG 표시와 앞뒷면 전환을 확인하고 캡처 |
| 공개 설치 API | 설치 JSON false/null, APK 다운로드 503, assetlinks 503 확인 |
| 웹 검사 | 모의 응답 6개 통과. 실제 localhost 브라우저는 CORS 허용 헤더 부재로 연결 실패 안내를 표시함 |
| 인증서/패키지 | 전달 APK 자체를 apksigner/aapt로 확인. Debug 코드 테스트와 release 서명 검증을 구분함 |

실서버 시험은 실제 Android 에뮬레이터에서 수행했다. HTTP 검증과 UI 검증 기록은 `verification/`에 포함했다.
초기 System UI ANR 및 ActivityScenario의 Intent 매칭 문제로 실패한 회차는 최종 통과로 계산하지 않았다.
테스트 계정/부적은 테스트용으로 생성됐다. 삭제 API가 이번 계약에 없어 자동 삭제하지 않았다. 비밀번호/JWT/공유 원본 토큰은 보고서에 기록하지 않았다.

## 대기·미확인 — 완료로 처리하지 않음

1. **공유 생성은 실제 서버에서 503**: `공유 링크 주소가 아직 설정되지 않았습니다.`
   유효 공유 링크 수령 성공, 같은 계정 재수령, 다른 계정 수령 409, 실제 카카오 전송·취소 후 재공유는 미확인이다. 존재하지 않는 수령 토큰 404만 실제 확인했다.
2. **카카오 키/플랫폼 등록 미제공**: SDK 2.26.0 연결 코드는 있으나 Native app key가 비어 있어 카카오 전송 버튼은 비활성화된다. SDK 전송 성공으로 표시하지 않았다.
3. **공유 도메인 미정**: 이 APK의 기본 HTTPS 수신 호스트는 API 서버 도메인이다. 최종 공유 웹 도메인이 정해지면 `bukku.shareHost`와 카카오 키를 설정해 같은 release 키로 재빌드한다.
4. **APK·assetlinks 미등록**: 백엔드에 APK 파일과 아래 식별 정보를 등록한 후 다운로드/앱 링크 검증이 필요하다. 프론트 소스 push만으로 APK가 Render에 등록되지는 않는다.
5. **설치 웹 배포 미완료**: 최종 공유 도메인 호스팅, `/share/*` rewrite, `/.well-known/assetlinks.json` 별도 제공과 API CORS 허용 또는 프록시 설정이 필요하다. API `/share/{token}` JSON을 웹 설치 화면으로 간주하지 않는다.
6. 실기기 설치→로그인→원래 링크 재방문, 5분 지난 이미지 URL 재발급, 실제 네트워크 단절 직후 저장 결과, 실제 로그인→고민→AI→저장 버튼까지의 전 과정 터치 E2E는 미확인이다.

## 백엔드 등록용 공개 값

```dotenv
ANDROID_PACKAGE_NAME=com.example.bukku
ANDROID_SHA256_CERT_FINGERPRINTS=["6C:81:15:36:22:07:B4:FF:1D:73:AD:B8:18:9C:C2:A5:33:FA:EC:FF:8A:7E:03:80:17:FF:BE:DC:CA:26:65:F9"]
```

APK 파일 등록/다운로드 설정과 최종 공유 주소는 백엔드에서 처리한다. 값이 확정되기 전 프론트에서 공유 주소를 임의 발급하지 않았다.
카카오 Developers에는 패키지명·키 해시와 실제 공유 Product Link 도메인을 등록해야 한다.
공식 SDK 참고: [시작하기](https://developers.kakao.com/docs/ko/android/getting-started), [카카오톡 공유](https://developers.kakao.com/docs/en/kakaotalk-share/android-link).

## 재빌드

기존 도구 버전은 유지했다. 추가 의존성은 카카오톡 공유 SDK `com.kakao.sdk:v2-share:2.26.0`이다.
Git에서 제외된 `android/local.properties`의 `bukku.shareHost`, `bukku.kakaoNativeKey`를 확정 값으로 설정한다.
`BUKKU_SIGNING_PROPERTIES`는 저장소 밖의 기존 서명 properties 경로를 가리키도록 하고 `assembleRelease`를 실행한다.
이번 컴퓨터의 서명 자료 보관 위치는 `C:/chg/bukku-signing`이며, 해당 내용을 전달 ZIP에 복사하지 않는다.
체크리스트의 자세한 표시 기준은 `docs/1111_api.md`를 따른다.
