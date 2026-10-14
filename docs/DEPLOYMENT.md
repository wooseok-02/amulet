# Render 백엔드 배포

상태: 2026-10-14 Render Web Service 배포 성공. 공개 Base URL은
`https://amulet-api.onrender.com`이다.

배포 저장소는 `wooseok-02/amulet`의 `back` 브랜치이며, 최초 배포 커밋은
`6923240`이다. 무료 인스턴스는 유휴 시 정지되어 첫 요청이 지연될 수 있다.

### 배포 직후 확인 결과

- `GET /health`: HTTP 200, `status: ok`, `environment: development`
- `GET /health/supabase`: HTTP 200, `status: ok`
- Render 배포 상태: `Live`
- Render 환경 변수 이름: `OPENAI_API_KEY`, `OPENAI_MODEL`,
  `SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_URL` 등록됨
- OpenAI 키는 값이 존재하며 `sk-` 형식이다. 2026-10-14 배포 API에서 가상 테스트
  계정으로 인증한 뒤 친구 운세 AI 생성 요청을 **한 번** 실행해 HTTP 200과 정상
  운세 응답을 확인했다. 이 검증으로 생성된 테스트 계정은 Supabase Auth에 남는다.
- 사용자가 Render에 `APP_ENV`를 추가했다고 알렸으나, 같은 날 두 차례 확인한
  공개 `/health`는 여전히 `development`를 반환했다. 새 설정이 실행 중인
  배포에 반영됐는지 Render 환경 변수 및 배포 상태를 확인해야 한다.

## Web Service 설정

| 항목 | 값 |
| --- | --- |
| GitHub 저장소 | `https://github.com/wooseok-02/amulet` |
| 배포 대상 브랜치 | `back` (현재 백엔드 작업 브랜치, 원격 최신 커밋 확인 후 선택) |
| Language | Python 3 |
| Root Directory | `backend` |
| Python | `backend/.python-version`의 Python 3.12 |
| Build Command | `pip install -r requirements.txt` |
| Start Command | `uvicorn app.main:app --host 0.0.0.0 --port $PORT` |
| Health Check Path | `/health` |

인스턴스 요금제 및 리전은 서비스 생성 시 결정한다.

## Render 환경 변수

Render의 Environment 설정에 등록한다. 실제 키를 Git이나 이 문서에 쓰지 않는다.

| 이름 | 값 |
| --- | --- |
| `APP_NAME` | `부꾸 API` |
| `APP_ENV` | `production` |
| `DEBUG` | `false` |
| `SUPABASE_URL` | 기존 백엔드 환경의 Supabase URL |
| `SUPABASE_PUBLISHABLE_KEY` | 기존 백엔드 환경의 Publishable key |
| `OPENAI_API_KEY` | 실제 호출 검증에 사용한 서버용 키 |
| `OPENAI_MODEL` | `gpt-6-luna` |

## 배포 후 확인

- [ ] Render 빌드 및 실행 로그에서 서비스 기동 성공 확인
- [x] 공개 HTTPS 주소의 `/health` 확인
- [x] `/health/supabase` 연결 확인
- [ ] `/docs`에서 API 명세 표시 확인
- [ ] 테스트 계정으로 회원가입·로그인·토큰 갱신 확인
- [x] 인증 토큰으로 친구 운세 AI 생성 요청 1회 확인
- [ ] 내 운세·내 고민·친구 상황 해석의 배포 경로 확인
- [ ] 공개 `/health`에서 `environment: production` 확인
- [ ] 프론트에 공개 Base URL, `/docs`, `WORK_PROGRESS.md` 전달

프론트 개발자는 배포 서버를 사용하면 백엔드 실행이나 OpenAI 키 설정이 필요 없다.

공식 안내: https://render.com/docs/deploy-fastapi
