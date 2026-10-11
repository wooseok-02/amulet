# 인증 연동

## 인증 방식

- Supabase Auth
- Supabase 발급 JWT 사용
- Android → FastAPI → Supabase
- Android에서 Supabase 직접 호출 없음

## API

| 기능 | Method | Endpoint |
| --- | --- | --- |
| 회원가입 | `POST` | `/auth/signup` |
| 로그인 | `POST` | `/auth/login` |
| 토큰 갱신 | `POST` | `/auth/refresh` |

## 인증 응답

```json
{
  "user_id": "사용자 UUID",
  "email": "user@example.com",
  "access_token": "JWT",
  "refresh_token": "갱신 토큰",
  "token_type": "bearer",
  "expires_in": 3600,
  "expires_at": 1800000000
}
```

## Android 처리

### 로그인·회원가입 성공

- `access_token` 저장
- `refresh_token` 저장

### 인증 API 요청

```http
Authorization: Bearer {access_token}
```

### Access Token 만료

- `/auth/refresh` 호출
- 새 `access_token`, `refresh_token`으로 교체

### Refresh Token 만료 또는 갱신 실패

- 저장된 토큰 삭제
- 로그인 화면 이동

## 역할 구분

| 영역 | 역할 |
| --- | --- |
| Supabase | 사용자 계정, 비밀번호, JWT 관리 |
| FastAPI | 회원가입·로그인·토큰 갱신 중계 |
| Android | 토큰 저장, 인증 헤더 전송, 자동 로그인 처리 |
