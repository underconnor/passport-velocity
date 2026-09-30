# 예정 소스 경계

아직 Java 소스는 없습니다. 부트스트랩 시 다음 경계로 구성합니다.

| 모듈 | 책임 |
| --- | --- |
| `bootstrap` | 플러그인 수명 주기와 의존성 구성 |
| `config` | API 주소, 대기 서버, 타임아웃 등 런타임 설정 |
| `api` | 계약 기반 비동기 HTTP 클라이언트 |
| `policy` | 접근 판정, 유효한 캐시, 정책 회수 |
| `routing` | 최초 목적지·모든 서버 이동 검사 |
| `link` | 개인별 일회용 링크와 게임 확인 |
| `command` | `/passport` 안내·상태·확인 명령 |
| `presentation` | Adventure 메시지와 접근 거부 안내 |

공유 모델은 `passport-contracts`의 특정 버전 배포본을 사용합니다. 인접 저장소 소스 참조, DB 직접 접속, 학교 파서와 Discord OAuth는 포함하지 않습니다.
