# 마이페이지 배포 서버 테스트 — 2026-11-17

대상: `https://amulet-api.onrender.com`  
소스 브랜치: `feature/mypage-backend`  
Render 배포 설정 문서의 대상 브랜치: `back`

기능 브랜치를 GitHub에 올린 뒤 1분 이상 지난 시점에 공개 서버를 호출했다.
이 문서는 배포된 서버에서 실제로 관찰한 결과만 완료로 표시한다.

| 순서 | 확인 항목 | 상태 | 결과 |
| --- | --- | --- | --- |
| 1 | `GET /health` | 완료 | 200, `status: ok`, `environment: development` |
| 2 | `GET /health/supabase` | 완료 | 200, `status: ok` |
| 3 | OpenAPI에 `/api/v1/me`, `/api/v1/auth/logout` 노출 | 실패 | 두 경로 모두 없음 |
| 4 | 토큰 없는 `GET /api/v1/me` → 401 | 실패 | 현재 서버에서 404 |
| 5 | 토큰 없는 `POST /api/v1/auth/logout` → 401 | 실패 | 현재 서버에서 404 |
| 6 | A 토큰 프로필 200, 필수 값·형식 및 B 정보 분리 | 대기 | 새 API 배포·A/B 테스트 토큰 필요 |
| 7 | 양력 `null`, 음력 평달 `false`, 음력 윤달 `true` | 대기 | 새 API 배포·해당 테스트 계정 필요 |
| 8 | 만료·변조 토큰 조회 401, 태어난 시간 미입력 `null` | 대기 | 새 API 배포·해당 테스트 계정 필요 |
| 9 | A 로그아웃 204·본문 없음, 같은 토큰 401·B 토큰 200 | 대기 | 새 API 배포·A/B 테스트 토큰 필요 |
| 10 | 로그아웃 서버 오류에서 204 미반환 | 대기 | 오류 재현 가능한 배포 테스트 환경 필요 |

현재 공개 서버에서는 `GET /api/v1/me`와 `POST /api/v1/auth/logout`이
각각 404 `{"detail":"Not Found"}`를 반환한다. 기능 브랜치는 배포 브랜치인
`back`에 통합되지 않았으므로, 6~10번을 통과로 표시할 수 없다.
