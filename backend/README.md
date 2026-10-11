# 부꾸 Backend

Python 3.12와 FastAPI를 사용하는 부꾸 백엔드다.

## 로컬 환경 준비

`backend` 디렉터리에서 아래 명령을 실행한다.

```bash
python3.12 -m venv mof
source mof/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

`mof/`와 `.env`는 로컬 전용이며 Git에 포함하지 않는다.

Supabase 프로젝트의 URL과 Publishable key를 `.env`에 설정한다.

```dotenv
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key
```

Secret key와 데이터베이스 비밀번호는 현재 연결에 사용하지 않는다.

## 개발 서버 실행

```bash
uvicorn app.main:app --reload
```

- 상태 확인: `http://127.0.0.1:8000/health`
- Supabase 연결 확인: `http://127.0.0.1:8000/health/supabase`
- API 문서: `http://127.0.0.1:8000/docs`

## 테스트

```bash
pytest
```

## 인증 API

회원가입, 로그인, 토큰 갱신은 모두 같은 세션 응답 형식을 사용한다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| `POST` | `/auth/signup` | 계정과 프로필 생성 |
| `POST` | `/auth/login` | 이메일·비밀번호 로그인 |
| `POST` | `/auth/refresh` | 자동 로그인을 위한 토큰 갱신 |

회원가입 요청 예시:

```json
{
  "email": "user@example.com",
  "password": "password1",
  "password_confirm": "password1",
  "name": "홍길동",
  "birth_date": "1990-01-02",
  "calendar_type": "SOLAR",
  "is_leap_month": null,
  "birth_time": "13:30",
  "gender": "MALE"
}
```

세션 응답 예시:

```json
{
  "user_id": "user-uuid",
  "email": "user@example.com",
  "access_token": "access-token",
  "refresh_token": "refresh-token",
  "token_type": "bearer",
  "expires_in": 3600,
  "expires_at": 1800000000
}
```

Android 앱은 로그인 또는 회원가입 성공 시 두 토큰을 안전한 로컬 저장소에
저장한다. `access_token` 만료 시 저장한 `refresh_token`을 `/auth/refresh`에
전달하고, 응답으로 받은 두 토큰을 모두 교체한다.
