# Passport Velocity

Velocity에서 정품 Minecraft Java 계정의 연결 상태와 서버별 접근 권한을 검사하는 중앙 접속 제어 플러그인입니다.

**현재 상태: 개발 준비 문서만 작성했습니다.** Java 소스, Gradle 설정, 플러그인 메타데이터와 JAR은 아직 없습니다.

## 역할

- 프록시의 정품 계정 인증을 전제로 Minecraft UUID를 식별합니다.
- 최초 접속과 모든 서버 이동에서 `passport-api`의 정책을 확인합니다.
- 미인증 사용자를 NanoLimbo 대기 서버로 보내고 클릭 가능한 개인별 웹 연결 링크를 안내합니다.
- 웹에서 본인 확인이 끝난 사용자에게 게임에서 `/passport confirm`으로 최종 확인하도록 합니다.
- 허용된 서버로의 이동, 접근 거부 안내, 정지·권한 회수를 처리합니다.
- API 통신과 짧은 정책 캐시를 관리하며 DB에 직접 접근하지 않습니다.

Discord ID는 사용자 입력 정보일 뿐이며 프록시 인증이나 접근 권한의 근거로 사용하지 않습니다.

## 예정 스택과 지원 기준

- Java 25 + Gradle Kotlin DSL
- Velocity 4.2.0 build 30을 초기 호환성 검증 대상으로 사용
- Adventure 채팅 컴포넌트로 링크와 상태 안내
- `passport-contracts`의 버전이 고정된 API 계약·생성 클라이언트 배포본

위 조합과 NanoLimbo 연동은 향후 빌드·실행 검증 대상입니다. 현재 준비 문서만으로 호환성을 확인한 것은 아닙니다.

## 다음 구현 순서

1. API의 접근 판정·일회용 연결·게임 확인 계약을 확정합니다.
2. 플러그인 부트스트랩과 비동기 API 클라이언트를 만듭니다.
3. NanoLimbo 대기 상태에서 개인별 링크와 `/passport confirm`을 검증합니다.
4. 최초 접속·서버 이동 검사와 접근 차단을 구현합니다.
5. 정책 회수·API 장애·재접속·중복 연결 흐름을 검증합니다.

세부 범위는 [구현 계획](docs/implementation-plan.md), 소스 경계는 [src 안내](src/README.md)를 참고하세요.

## 관련 저장소

개인 계정의 관련 저장소입니다.

- [passport-api](https://github.com/underconnor/passport-api): 연결 상태와 서버 접근 정책
- [passport-contracts](https://github.com/underconnor/passport-contracts): API 계약과 배포 산출물
- [passport-web](https://github.com/underconnor/passport-web): 학교 인증과 웹 확인
- [passport-paper](https://github.com/underconnor/passport-paper): 백엔드 서버 방어와 prefix 표시

각 저장소는 독립적으로 빌드·배포합니다. 다른 저장소의 `../src`를 직접 참조하지 않습니다.
