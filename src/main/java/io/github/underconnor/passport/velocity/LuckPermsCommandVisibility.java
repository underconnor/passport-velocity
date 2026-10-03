package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.plugin.PluginManager;
import java.util.*;
import java.util.concurrent.*;

/** LuckPerms accepts its root without permission, but filters its own subcommand completions. */
final class LuckPermsCommandVisibility {
    private static final Set<String> ALIASES=Set.of("lp","lpv","luckperms","luckpermsvelocity","perm","perms","permission","permissions");
    private static final long TIMEOUT_MILLIS=250;
    private LuckPermsCommandVisibility() {}

    static CompletableFuture<Set<String>> hiddenRoots(Collection<String> roots,CommandSource source,
                                                       CommandManager commands,PluginManager plugins) {
        Map<String,CompletableFuture<Boolean>> checks=new LinkedHashMap<>();
        for(String root:roots) {
            if(!commands.hasCommand(root,source) || !ownedByLuckPerms(root,commands,plugins)) continue;
            // This calls the registered command directly, not the available-commands event.
            // LP's tabCompleteCommand authorizes each subcommand using its current context.
            checks.put(root,CompletableFuture.supplyAsync(() -> commands.offerSuggestions(source,root+" "))
                .thenCompose(result -> result)
                .thenApply(suggestions -> suggestions!=null && suggestions.stream().anyMatch(value -> value!=null && !value.isBlank()))
                .completeOnTimeout(false,TIMEOUT_MILLIS,TimeUnit.MILLISECONDS)
                .exceptionally(error -> false));
        }
        return CompletableFuture.allOf(checks.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Set<String> hidden=new HashSet<>();
            checks.forEach((root,allowed) -> { if(!allowed.join()) hidden.add(root); });
            return Set.copyOf(hidden);
        });
    }

    private static boolean ownedByLuckPerms(String root,CommandManager commands,PluginManager plugins) {
        if(!commands.hasCommand(root)) return false; // A backend's permissions are evaluated by Paper.
        var meta=commands.getCommandMeta(root);
        if(meta!=null && meta.getPlugin()!=null) {
            var owner=plugins.fromInstance(meta.getPlugin());
            if(owner.isPresent()) return owner.get().getDescription().getId().equalsIgnoreCase("luckperms");
        }
        // LP 5.5 registers through the legacy overload, which has no plugin owner metadata.
        String base=root.toLowerCase(Locale.ROOT);
        base=base.substring(base.lastIndexOf(':')+1);
        while(base.startsWith("/")) base=base.substring(1);
        return ALIASES.contains(base);
    }
}
