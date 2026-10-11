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
OPENAI_API_KEY=sk-your-key
OPENAI_MODEL=gpt-6-luna
```

Secret key와 데이터베이스 비밀번호는 현재 연결에 사용하지 않는다.
OpenAI API Key는 Android 앱에 넣지 않고 백엔드 환경 변수로만 관리한다.

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

## 오늘 운세 API

두 API 모두 아래 인증 헤더가 필요하다.

```http
Authorization: Bearer {access_token}
```

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| `GET` | `/fortune/today` | 오늘 운세 조회 또는 최초 생성 |
| `POST` | `/fortune/interpret` | 고민과 오늘 운세를 이용한 맞춤 해석 |

오늘 운세 응답 예시:

```json
{
  "fortune_date": "2026-10-12",
  "headline": "흐름이 천천히 열리는 날",
  "total_comment": "서두르기보다 준비한 것을 차분히 보여주세요.",
  "total_score": 78,
  "love_score": 72,
  "money_score": 68,
  "career_score": 81,
  "study_score": 86,
  "health_score": 74,
  "lucky_number": 7,
  "lucky_color": "초록",
  "today_action": "질문 한 번 더"
}
```

맞춤 해석 요청 예시:

```json
{
  "category": "취업",
  "concern": "첫 면접을 앞두고 준비한 말을 잘 전달할지 걱정돼요.",
  "mood": "불안해요"
}
```

맞춤 해석 응답 예시:

```json
{
  "day_message": "진심 있게 답하는 태도가 빛나는 날이에요.",
  "action_advice": "첫 문장을 소리 내어 한 번 연습해보세요."
}
```

오늘 운세는 한국 날짜 기준으로 사용자마다 하루에 하나만 저장한다. 고민 입력과
맞춤 해석 결과는 저장하지 않는다.

## 친구 운세 API

친구 운세도 인증 헤더가 필요하다. 이름·별명과 관계는 Android 화면에서만
사용하며 API에 보내지 않는다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| `POST` | `/fortune/friend/today` | 친구 오늘 운세 생성 |
| `POST` | `/fortune/friend/interpret` | 친구 상황 맞춤 해석 |

친구 운세 요청 예시:

```json
{
  "birth_date": "1998-03-21",
  "calendar_type": "SOLAR",
  "is_leap_month": null,
  "birth_time": null,
  "gender": null
}
```

친구 운세 응답 예시:

```json
{
  "fortune_date": "2026-10-12",
  "headline": "준비한 만큼 자신감이 빛나는 날",
  "total_comment": "오늘은 익숙한 사람의 응원이 큰 힘이 되는 날이에요.",
  "total_score": 82,
  "lucky_number": 3,
  "lucky_color": "하늘",
  "support_action": "먼저 연락하기"
}
```

친구 맞춤 해석 요청 예시:

```json
{
  "profile": {
    "birth_date": "1998-03-21",
    "calendar_type": "SOLAR",
    "is_leap_month": null,
    "birth_time": null,
    "gender": null
  },
  "fortune": {
    "fortune_date": "2026-10-12",
    "headline": "준비한 만큼 자신감이 빛나는 날",
    "total_comment": "오늘은 익숙한 사람의 응원이 큰 힘이 되는 날이에요.",
    "total_score": 82,
    "lucky_number": 3,
    "lucky_color": "하늘",
    "support_action": "먼저 연락하기"
  },
  "concern": {
    "category": "전체",
    "situation": "중요한 면접을 앞둔 친구에게 자신감을 주고 싶어요."
  }
}
```

친구 맞춤 해석 응답은 내 고민 해석과 같은 형식을 사용한다.

```json
{
  "day_message": "지금까지 쌓아온 준비가 친구의 가장 단단한 행운이에요.",
  "action_advice": "친구가 스스로의 노력을 믿을 수 있도록 응원해주세요."
}
```

친구 정보, 친구 운세, 상황과 해석 결과는 DB에 저장하지 않는다.
