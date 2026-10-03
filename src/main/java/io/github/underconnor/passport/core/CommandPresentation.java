package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.*;

/** Presentation rules never grant execution permission or infer backend permissions from command names. */
public final class CommandPresentation {
    public static final String INSPECT_PERMISSION="passport.commands.inspect";
    private static final Set<String> INFORMATION=Set.of("plugins","pl","version","ver","about","icanhasbukkit");
    private CommandPresentation() {}
    public static String root(String command) { return command.stripLeading().split("\\s+",2)[0].toLowerCase(Locale.ROOT); }
    public static boolean informationRoot(String root) {
        String normalized=root.toLowerCase(Locale.ROOT);
        String base=normalized.substring(normalized.lastIndexOf(':')+1);
        return INFORMATION.contains(base) || normalized.contains(":") && Set.of("help","?").contains(base);
    }
    public static boolean velocityRoot(String root) { return Set.of("velocity","velocity:velocity").contains(root.toLowerCase(Locale.ROOT)); }
    public static boolean informationCommand(String command) {
        String[] parts=command.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if(informationRoot(parts[0])) return true;
        // The root/unknown-subcommand response itself contains proxy information. Reload keeps its own permission.
        return velocityRoot(parts[0]) && (parts.length==1 || !parts[1].equals("reload"));
    }
    public static boolean administrator(Policy policy,UUID uuid,boolean currentSession,boolean locallyDenied,Instant now) {
        return currentSession && !locallyDenied && policy!=null && policy.minecraftUuid().equals(uuid)
            && policy.valid(now) && policy.administrator();
    }
    public static List<String> passportCommands(Policy policy,UUID uuid,boolean currentSession,boolean locallyDenied,boolean console,Instant now) {
        if(console) return List.of("help","player","adminweb","announce");
        if(!currentSession) return List.of();
        List<String> commands=new ArrayList<>(List.of("help","status","web","link","queue"));
        boolean servers=policy!=null && policy.minecraftUuid().equals(uuid) && policy.active(now) && !policy.allowedServerIds().isEmpty();
        if(servers) commands.add("server");
        if(administrator(policy,uuid,currentSession,locallyDenied,now)) {
            commands.addAll(List.of("player","adminweb","announce"));
            if(servers) commands.add("tp");
        }
        return List.copyOf(commands);
    }
}
