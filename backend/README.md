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

## 개발 서버 실행

```bash
uvicorn app.main:app --reload
```

- 상태 확인: `http://127.0.0.1:8000/health`
- API 문서: `http://127.0.0.1:8000/docs`

## 테스트

```bash
pytest
```
