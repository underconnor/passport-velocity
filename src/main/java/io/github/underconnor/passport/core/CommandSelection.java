package io.github.underconnor.passport.core;
import java.util.*;
import java.time.Instant;
/** Plain exact matching keeps names from becoming commands and refuses ambiguous display labels. */
public final class CommandSelection {
    private CommandSelection() {}
    public static List<String> servers(Policy policy,String query) {
        if(policy.hasServerCommandNames()) {
            String command=Policy.normalizeCommand(query);
            return policy.allowedServerIds().stream().filter(id -> policy.commandName(id).equals(command)).sorted().toList();
        }
        List<String> byId=policy.allowedServerIds().stream().filter(id -> id.equalsIgnoreCase(query)).sorted().toList();
        return byId.isEmpty() ? policy.allowedServerIds().stream().filter(id -> policy.label(id).equalsIgnoreCase(query)).sorted().toList() : byId;
    }
    public static List<String> serverSuggestions(Policy policy,String prefix) {
        if(policy.hasServerCommandNames()) return suggestions(policy.serverCommandNames().values(),Policy.normalizeCommand(prefix));
        List<String> names=new ArrayList<>(policy.allowedServerIds());
        for(String id:policy.allowedServerIds()) if(!policy.label(id).contains(" ")) names.add(policy.label(id));
        return suggestions(names,prefix);
    }
    public static Optional<String> visibleServerLabel(Policy policy,String id,Instant now) {
        return policy.allows(id,now) ? Optional.ofNullable(policy.serverLabels().get(id)).filter(label -> !label.isBlank()) : Optional.empty();
    }
    public static List<String> suggestions(Collection<String> candidates,String prefix) {
        String normalized=prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> !value.isBlank() && value.codePoints().noneMatch(Character::isISOControl))
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER).limit(50).toList();
    }
    public static boolean blockedBuiltin(String command) {
        String root=command.stripLeading().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        return root.equals("server") || root.equals("velocity:server");
    }
    public static boolean safeAnnouncement(String message) {
        return !message.isBlank() && message.codePointCount(0,message.length())<=300 && message.codePoints().noneMatch(Character::isISOControl);
    }
}
