# 소스 구조

`main/java/.../core`는 중앙 API HTTP 클라이언트와 정책 검사, `main/java/.../velocity`는 서버 이벤트 연동입니다. `test`는 lease·버전·회수·UUID·HTTP 인증·리다이렉트·시간 제한을 검사합니다.

`PolicyEvents`는 outbox 응답을 검증하고, `PolicyEventPoller`는 온라인 UUID 재조회가 성공한 batch만 승인합니다. `PolicyRefreshes`는 UUID별 HTTP 요청을 공유하고 동시 실행을 제한합니다.
