package io.github.underconnor.passport.core;

/** Player guidance changes no API authorization or link-consumption rules. */
public record LinkFeedback(String message, boolean refreshPolicy) {
    private static final LinkFeedback UNKNOWN = new LinkFeedback("Passport 인증 또는 서버 권한을 확인할 수 없습니다.", false);

    public static LinkFeedback confirmation(Throwable error) {
        return switch (ApiFailure.reasonOf(error)) {
            case GAME_CONFIRMATION_CONSUMED -> new LinkFeedback("웹에서 계정 연결을 완료해 주세요.", false);
            case LINK_EXPIRED -> new LinkFeedback("연결 요청이 만료되었습니다. /passport 로 새 링크를 받으세요.", false);
            case LINK_CONSUMED -> new LinkFeedback("연결 요청이 완료되었거나 취소되었습니다. /passport status로 현재 상태를 확인하세요.", true);
            case GAME_SESSION_MISMATCH -> new LinkFeedback("현재 접속과 연결 요청의 세션이 다릅니다. /passport 로 새 연결을 시작하세요.", false);
            case LINK_NOT_FOUND -> new LinkFeedback("연결 요청을 찾을 수 없습니다. /passport 로 새 연결을 시작하세요.", false);
            default -> UNKNOWN;
        };
    }

    public static LinkFeedback creation(Throwable error) {
        return ApiFailure.reasonOf(error) == ApiFailure.Reason.MINECRAFT_ALREADY_LINKED
            ? new LinkFeedback("이미 계정이 연결되어 있습니다. /passport status로 서버 접근 상태를 확인하세요.", true)
            : UNKNOWN;
    }
}
