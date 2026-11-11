# 미설치 사용자 안내 화면

정적 파일을 확정된 공유 도메인에 HTTPS로 호스팅하고 `/share/*`를 `index.html`로 rewrite한다.
`_redirects`는 이를 지원하는 정적 호스트용 예시이며 다른 호스트에서는 같은 rewrite를 설정한다.
최종 도메인은 아직 문서에 없으므로 이 저장소에서 임의로 발급하거나 배포하지 않았다.

프론트 호스트에서 API 서버로 접근할 수 있도록 백엔드의 CORS 허용 또는 같은 호스트의 API 프록시 구성이 필요하다.
공유 도메인의 `/.well-known/assetlinks.json`은 rewrite에서 제외하고, 백엔드가 제공하는 실제 패키지명·서명 지문을 등록한다.
API 서버의 `/share/{token}`은 JSON이며 이 HTML을 대신하지 않는다.

- `/app/install` 또는 `/share/{token}`의 설치 JSON을 확인한다. 로그인·수령 요청은 하지 않는다.
- APK 미등록: 준비 중, 다운로드 버튼 숨김.
- APK 등록: 서버 `/app/download`로 이동한다. 서버의 307 외부 다운로드 리다이렉트는 브라우저가 처리한다.
- 설치 후 원래 링크 재방문을 안내한다. 원래 공유 토큰을 유지하며 웹 방문으로 선물을 수령하지 않는다.
- Android 앱의 `bukku.shareHost`, 카카오 Developers Product Link 도메인, 백엔드 `AMULET_SHARE_BASE_URL`을 동일한 확정 도메인에 맞춘다.

현재 미확인: 공유 도메인 호스팅·CORS·미설치 실기기 다운로드/설치/재진입. 파일 구현만으로 완료 체크하지 않는다.
