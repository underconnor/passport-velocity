package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.proxy.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.stub;
import static org.junit.jupiter.api.Assertions.*;

class LuckPermsCommandVisibilityTest {
    @Test void permissiveProxyRootsAreRemovedAndBackendPermissionContextsRemainUntouched() throws Exception {
        var fixture=new Fixture();
        fixture.proxyRoots.addAll(List.of("lpv","luckpermsvelocity","luckperms:lpv","/lpv"));
        var tree=fixture.filter("lpv","luckpermsvelocity","luckperms:lpv","/lpv","lp","perm","fawe","mv","home");
        assertEquals(Set.of("lp","perm","fawe","mv","home"),CommandTreeFilterTest.names(tree));
        assertEquals(Set.of("lpv ","luckpermsvelocity ","luckperms:lpv ","/lpv "),new HashSet<>(fixture.probes));
    }
    @Test void granularNativePermissionKeepsRootsWithoutRequiringInspectorOrAnLpWildcard() throws Exception {
        var fixture=new Fixture(); fixture.proxyRoots.addAll(List.of("lpv","luckpermsvelocity"));
        fixture.complete=command -> CompletableFuture.completedFuture(List.of("user"));
        assertEquals(Set.of("lpv","luckpermsvelocity","home"),CommandTreeFilterTest.names(fixture.filter("lpv","luckpermsvelocity","home")));
        assertEquals(Tristate.UNDEFINED,fixture.player.getPermissionValue("luckperms.*"));
    }
    @Test void consoleOnlySlashAliasesRemainHiddenEvenWhenNormalLpCommandsAreAllowed() throws Exception {
        var fixture=new Fixture(); fixture.proxyRoots.addAll(List.of("lpv","/lpv","/luckpermsvelocity"));
        fixture.deniedRoots.addAll(List.of("/lpv","/luckpermsvelocity"));
        fixture.complete=command -> CompletableFuture.completedFuture(List.of("editor"));
        assertEquals(Set.of("lpv"),CommandTreeFilterTest.names(fixture.filter("lpv","/lpv","/luckpermsvelocity")));
        assertEquals(List.of("lpv "),fixture.probes);
    }
    @Test void inspectionPermissionDoesNotOverrideLuckPermsNativeDeny() throws Exception {
        var fixture=new Fixture(); fixture.inspect=true; fixture.proxyRoots.add("lpv");
        assertEquals(Set.of("plugins","home"),CommandTreeFilterTest.names(fixture.filter("lpv","plugins","home")));
    }
    @Test void customOwnedAliasesAreCheckedButKnownAliasesOwnedByAnotherPluginAreNot() throws Exception {
        var fixture=new Fixture(); fixture.owned("permissions-admin","luckperms"); fixture.owned("perm","ordinary-gameplay");
        assertEquals(Set.of("perm"),CommandTreeFilterTest.names(fixture.filter("permissions-admin","perm")));
        assertEquals(List.of("permissions-admin "),fixture.probes);
    }
    @Test void failedOrMalformedCompletionsHideOnlyTheAffectedProxyRoot() throws Exception {
        for(Function<String,CompletableFuture<List<String>>> response:List.<Function<String,CompletableFuture<List<String>>>>of(
                command -> { throw new IllegalStateException("unavailable"); },
                command -> CompletableFuture.failedFuture(new IllegalStateException("unavailable")),
                command -> CompletableFuture.completedFuture(null),
                command -> CompletableFuture.completedFuture(List.of(""," ")))) {
            var fixture=new Fixture(); fixture.proxyRoots.add("lpv"); fixture.complete=response;
            assertEquals(Set.of("lp","home"),CommandTreeFilterTest.names(fixture.filter("lpv","lp","home")));
        }
    }
    @Test void stuckProbeTimesOutAndLateCompletionCannotChangeTheSentTree() throws Exception {
        var fixture=new Fixture(); fixture.proxyRoots.add("lpv"); var pending=new CompletableFuture<List<String>>();
        fixture.complete=command -> pending;
        var tree=fixture.filter("lpv","home"); // EventTask must finish within the fixture's two-second bound.
        assertEquals(Set.of("home"),CommandTreeFilterTest.names(tree));
        assertFalse(pending.isDone()); // Do not time out or otherwise mutate LP's original future.
        pending.complete(List.of("editor"));
        assertEquals(Set.of("home"),CommandTreeFilterTest.names(tree));
    }
    @Test void synchronousCompletionProviderCannotBlockTheAvailableCommandsEvent() throws Exception {
        var fixture=new Fixture(); fixture.proxyRoots.add("lpv"); var release=new CompletableFuture<Void>();
        fixture.complete=command -> { release.join(); return CompletableFuture.completedFuture(List.of("editor")); };
        try { assertEquals(Set.of("home"),CommandTreeFilterTest.names(fixture.filter("lpv","home"))); }
        finally { release.complete(null); }
    }
    @Test void permissionRevocationIsRecheckedOnTheNextCommandTree() throws Exception {
        var fixture=new Fixture(); fixture.proxyRoots.add("lpv");
        fixture.complete=command -> CompletableFuture.completedFuture(List.of("editor"));
        assertNotNull(fixture.filter("lpv").getChild("lpv"));
        fixture.complete=command -> CompletableFuture.completedFuture(List.of());
        assertNull(fixture.filter("lpv").getChild("lpv"));
    }

    private static final class Fixture {
        boolean inspect;
        final Set<String> proxyRoots=new HashSet<>();
        final Set<String> deniedRoots=new HashSet<>();
        final Map<String,CommandMeta> metadata=new HashMap<>();
        final Map<Object,PluginContainer> owners=new HashMap<>();
        final List<String> probes=new CopyOnWriteArrayList<>();
        Function<String,CompletableFuture<List<String>>> complete=command -> CompletableFuture.completedFuture(List.of());
        final Player player=stub(Player.class,(method,args) -> switch(method.getName()) {
            case "getPermissionValue" -> inspect && args[0].equals("passport.commands.inspect") ? Tristate.TRUE : Tristate.UNDEFINED;
            default -> null;
        });
        final CommandManager commands=stub(CommandManager.class,(method,args) -> switch(method.getName()) {
            case "hasCommand" -> proxyRoots.contains((String)args[0]) && (args.length==1 || !deniedRoots.contains((String)args[0]));
            case "getCommandMeta" -> metadata.get((String)args[0]);
            case "offerSuggestions" -> { probes.add((String)args[1]); yield complete.apply((String)args[1]); }
            default -> throw new AssertionError("Unexpected command-manager call: "+method.getName());
        });
        final PluginManager plugins=stub(PluginManager.class,(method,args) -> switch(method.getName()) {
            case "fromInstance" -> Optional.ofNullable(owners.get(args[0]));
            default -> null;
        });
        void owned(String alias,String pluginId) {
            Object instance=new Object();
            PluginDescription description=stub(PluginDescription.class,(method,args) -> method.getName().equals("getId") ? pluginId : null);
            owners.put(instance,stub(PluginContainer.class,(method,args) -> method.getName().equals("getDescription") ? description : null));
            metadata.put(alias,stub(CommandMeta.class,(method,args) -> method.getName().equals("getPlugin") ? instance : null));
            proxyRoots.add(alias);
        }
        com.mojang.brigadier.tree.RootCommandNode<CommandSource> filter(String... roots) throws Exception {
            var tree=new CommandTreeFilterTest().tree(roots);
            ProxyServer proxy=stub(ProxyServer.class,(method,args) -> switch(method.getName()) {
                case "getCommandManager" -> commands; case "getPluginManager" -> plugins; default -> null;
            });
            var plugin=new PassportVelocity(proxy,stub(Logger.class,(method,args) -> null));
            AdmissionEventsTest.await(plugin.availableCommands(new PlayerAvailableCommandsEvent(player,tree)));
            return tree;
        }
    }
}
