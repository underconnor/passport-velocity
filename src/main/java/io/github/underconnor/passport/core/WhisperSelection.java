package io.github.underconnor.passport.core;

import java.text.Normalizer;
import java.util.*;

/** Names are plain exact identifiers; IGN wins over any ambiguous real-name match. */
public final class WhisperSelection {
    public static final int MAX_MESSAGE_LENGTH=300;
    public static final String PERMISSION="passport.whisper";
    public static final Set<String> ROOTS=Set.of("msg","tell","w","귓");
    public record Entry(UUID uuid,String ign,String realName) {}
    private WhisperSelection() {}
    public static List<Entry> targets(Collection<Entry> players,String query) {
        if(!safeQuery(query)) return List.of();
        String normalized=normalize(query);
        List<Entry> byIgn=players.stream().filter(player -> normalize(player.ign()).equals(normalized)).toList();
        return (byIgn.isEmpty() ? players.stream().filter(player -> !player.realName().isBlank()
            && normalize(player.realName()).equals(normalized)).toList() : byIgn).stream()
            .sorted(Comparator.comparing(Entry::ign,String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public static List<String> suggestions(Collection<Entry> players,UUID sender,String prefix) {
        var candidates=new ArrayList<String>();
        for(Entry player:players) if(!player.uuid().equals(sender)) {
            candidates.add(player.ign());
            if(!player.realName().isBlank() && player.realName().codePoints().noneMatch(Character::isWhitespace)) candidates.add(player.realName());
        }
        return CommandSelection.suggestions(candidates,normalize(prefix));
    }
    public static boolean safeQuery(String query) {
        return !query.isBlank() && query.codePointCount(0,query.length())<=40
            && query.codePoints().noneMatch(point -> Character.isISOControl(point) || Character.isWhitespace(point));
    }
    public static boolean safeMessage(String message) {
        return !message.isBlank() && message.codePointCount(0,message.length())<=MAX_MESSAGE_LENGTH
            && message.codePoints().noneMatch(Character::isISOControl);
    }
    public static boolean owns(String command) {
        return ROOTS.contains(command.stripLeading().split("\\s+",2)[0].toLowerCase(Locale.ROOT));
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
