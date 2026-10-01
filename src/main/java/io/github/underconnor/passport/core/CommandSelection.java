package io.github.underconnor.passport.core;
import java.util.*;
/** Plain exact matching keeps names from becoming commands and refuses ambiguous display labels. */
public final class CommandSelection {
    private CommandSelection() {}
    public static List<String> servers(Policy policy,String query) {
        List<String> byId=policy.allowedServerIds().stream().filter(id -> id.equalsIgnoreCase(query)).sorted().toList();
        return byId.isEmpty() ? policy.allowedServerIds().stream().filter(id -> policy.label(id).equalsIgnoreCase(query)).sorted().toList() : byId;
    }
    public static boolean blockedBuiltin(String command) {
        String root=command.stripLeading().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        return root.equals("server") || root.equals("velocity:server");
    }
    public static boolean safeAnnouncement(String message) {
        return !message.isBlank() && message.codePointCount(0,message.length())<=300 && message.codePoints().noneMatch(Character::isISOControl);
    }
}
