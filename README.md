# passport-velocity

NanoLimbo 대기 서버 안내, 웹 계정 연결, 중앙 정책에 따른 서버 이동 제어를 구현한 첫 개발 버전입니다.

대상 API는 Velocity **4.2.0**이며 Gradle 설정에서 고정했습니다. 실제 서버 호환 대상은 4.2.0 build 30 / Minecraft 26.2입니다. 산출물: `build/libs/passport-velocity-0.1.0-SNAPSHOT.jar`.

## 구현한 동작

- online-mode 프록시의 정품 계정 접속마다 새로운 게임 세션 ID 생성
- 최초 목적지를 NanoLimbo 대기 서버로 지정하고 개인별 클릭 인증 링크 발급
- `/passport`, `/passport status`, `/passport confirm`, `/passport cancel`
- UUID·게임 세션에 묶인 웹/게임 확인 API, 종료 시 pending 연결 취소 시도
- 모든 보호 서버 이동에 정책 검사, 민감 서버는 이동 시 API 재조회
- API 장애에서는 기존의 유효 lease만 인정. 만료·거부 정책은 대기실로 이동하며 이동 실패 시 접속 종료
- 20–25초 간격 정책 재조회, 1초 간격 만료 확인. 웹 확인이 마지막에 끝나도 다음 조회 시 허용된 기본 서버로 이동
- 보호 서버에서 kick되면 대기 서버로만 이동. 대기 서버 장애 시 종료

대기 서버는 upstream NanoLimbo를 별도로 실행하고 Velocity 서버 목록에 등록합니다. 책·인벤토리 GUI는 제공하지 않습니다. 서명된 채팅 본문을 바꾸지 않고 프록시 시스템 메시지를 보냅니다.

## 정책 변경 알림

서비스 Bearer 인증으로 `/v1/minecraft/events`를 2초마다 조회합니다. 처음이나 `reset:true` 응답에서는 온라인 UUID 전체의 정책을 다시 받고, 이후에는 변경된 온라인 UUID만 조회합니다. 동일 UUID의 이벤트는 가장 높은 정책 버전 하나로 합칩니다. 이벤트 내용 자체로 접속 권한을 부여하지 않습니다.

이벤트 batch는 하나만 처리하며, 필요한 정책 조회와 적용이 모두 성공한 뒤에만 cursor를 전진시킵니다. API 오류·낮은 버전 응답·부분 실패에서는 다음 poll에 재시도합니다. 일반 정기 조회와 명령·입장 검사도 UUID별 진행 중 요청을 공유합니다. 정책 조회의 동시 실행은 8개로 제한하고 나머지는 대기시켜 큰 batch가 HTTP 요청 한도를 소진하지 않도록 합니다.

정상 전달 시 **2초 poll + 최대 2초 정책 조회, 약 5초 내 권한 회수 반영**을 목표로 합니다. 접속자 수·HTTP 대기열·서버 tick·대기실 연결 지연에 따라 늘어날 수 있으며, 실제 Minecraft 클라이언트에서 측정한 보장 수치는 아닙니다. 이벤트 API 장애 시에도 기존 정기 조회와 60초 이하 lease 만료 차단은 유지됩니다. [복구 절차](docs/outbox-recovery.md)를 참고하세요.

## Velocity 환경 변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `PASSPORT_WAITING_SERVER` | `passport-limbo` | 등록된 대기 서버 ID |
| `PASSPORT_DEFAULT_SERVER` | `lobby` | 인증 후 허용 상태일 때 이동할 기본 서버 |
| `PASSPORT_WEB_ORIGIN` | `https://passport.example` | 인증 링크에서 허용하는 정확한 scheme/host/port |
| `PASSPORT_SENSITIVE_SERVERS` | 빈 값 | 매 이동 시 온라인 확인할 서버 ID, 쉼표 구분 |

잘못된 설정에서는 플러그인이 로그인과 서버 이동을 차단합니다. 대기 서버 ID와 기본 서버 ID는 달라야 합니다. `announce-proxy-commands` 활성화를 권장합니다. 명령·접속 이벤트를 바꾸는 다른 플러그인과의 공존 검증이 필요합니다.

## 빌드 및 검사

JDK 25가 필요합니다. 각 저장소를 독립적으로 clone한 뒤 실행합니다.

```sh
./gradlew --no-daemon clean build
```

Gradle 9.4.0 wrapper와 배포 ZIP SHA-256을 고정했습니다. 라이브러리 잠금 파일 및 검증 체크섬을 포함하며, 비공개 contracts 저장소 또는 옆 폴더를 빌드 중 읽지 않습니다. `core` 패키지는 draft 계약의 소비자 구현을 각 저장소가 소유합니다. 계약 변경 시 두 소비자 테스트를 함께 갱신합니다.

## 공통 환경 변수

| 변수 | 용도 |
|---|---|
| `PASSPORT_API_BASE_URL` | 중앙 API 주소. 기본값은 예시 주소이므로 운영값 필요 |
| `API_SERVICE_TOKEN` | 32자 이상의 서비스 Bearer 토큰. 저장소·플러그인 설정 파일에 넣지 않음 |
| `PASSPORT_ALLOW_INSECURE_HTTP` | 기본 false. 격리된 사설망 개발 HTTP에만 명시적으로 true |

HTTP 요청은 2초 제한, 동시 요청은 최대 32개입니다. 리다이렉트는 따라가지 않습니다. UUID·계약 버전·상태·서버 목록·정책 버전·최대 60초 lease를 검사합니다. 2초 이내의 시계 차이는 issuedAt 검사에만 허용하며 만료 시각을 연장하지 않습니다. 운영 서버 시간 동기화가 필요합니다.

UUID별 버전 기준은 로그아웃해도 프로세스 메모리에 남습니다. 낮은 버전 또는 같은 버전의 이전 issuedAt 응답은 버립니다. 내용과 issuedAt이 완전히 같은 응답은 현재 lease를 확인하는 데만 사용하며 만료를 연장하지 않습니다. 프로세스 재시작 후에는 새 유효 API 응답이 오기 전까지 허가하지 않습니다.

## 현재 한계

- 실제 정품 계정의 웹·게임 양쪽 확인 전체 흐름은 아직 실기기 검증 전입니다.
- 학교 인증·회원 명부의 실제 연결은 API 저장소의 제공자 준비 상태에 따릅니다. 개발 fixture 검증은 학교 본인 인증을 증명하지 않습니다.
- SSE 대신 DB outbox를 2초 간격으로 poll합니다. 약 5초 회수 목표의 실제 클라이언트 측정은 아직 남아 있습니다.
- 플러그인은 방화벽·프록시 forwarding 설정을 대신하지 않습니다. Paper 직접 접속 차단과 현대식 forwarding은 운영 배치의 필수 조건입니다.
- 서비스별 토큰 분리·회전은 운영 구성 단계의 후속 작업입니다. 현재 API와 공유된 서비스 토큰을 사용합니다.
