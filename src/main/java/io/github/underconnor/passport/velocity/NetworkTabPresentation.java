package io.github.underconnor.passport.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** One proxy-owned header/footer survives backend changes and includes the authentication room. */
final class NetworkTabPresentation {
    private NetworkTabPresentation() {}
    static Component header() {
        return Component.text("숭실대학교 AI소프트웨어학부 소모임 오버월드",NamedTextColor.WHITE)
            .append(Component.newline()).append(Component.text("overworld.flyjung.kr",NamedTextColor.AQUA));
    }
    static Component footer(int online) {
        return Component.text("현재 접속 중 · "+Math.max(0,online)+"명",NamedTextColor.WHITE);
    }
}
