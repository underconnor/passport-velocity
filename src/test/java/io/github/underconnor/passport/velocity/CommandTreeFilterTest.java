package io.github.underconnor.passport.velocity;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.*;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.permission.Tristate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandTreeFilterTest {
    CommandSource source=AdmissionEventsTest.stub(CommandSource.class,(method,args) -> null);
    RootCommandNode<CommandSource> tree(String... commands) {
        RootCommandNode<CommandSource> root=new RootCommandNode<>();
        for(String command:commands) root.addChild(LiteralArgumentBuilder.<CommandSource>literal(command).build());
        return root;
    }
    @Test void infoAliasesAreHiddenWhilePaperHelpAndOrdinaryGameplayCommandsRemain() {
        var root=tree("plugins","pl","version","bukkit:plugins","minecraft:help","bukkit:?","help","?","도움말","tpa","home","back","lobby","서버","adminhome","essentials:home");
        CommandTreeFilter.filter(root,source,false,name -> false,name -> false);
        assertEquals(Set.of("help","?","도움말","tpa","home","back","lobby","서버","adminhome","essentials:home"),names(root));
        assertNull(root.getChild("plugins")); // removes Brigadier child indexes, not just presentation iteration
    }
    @Test void backendPermissionPredicatesAreNotEvaluatedWithAProxySource() {
        var root=tree(); root.addChild(LiteralArgumentBuilder.<CommandSource>literal("home").requires(ignored -> false).build());
        CommandTreeFilter.filter(root,source,false,name -> false,name -> false);
        assertNotNull(root.getChild("home"));
    }
    @Test void proxyRootAndChildPermissionsAreFilteredWithoutGrantingNewPermission() {
        var root=tree("proxy-denied","ordinary");
        root.addChild(LiteralArgumentBuilder.<CommandSource>literal("proxy")
            .then(LiteralArgumentBuilder.<CommandSource>literal("allowed").requires(ignored -> true))
            .then(LiteralArgumentBuilder.<CommandSource>literal("denied").requires(ignored -> false)).build());
        CommandTreeFilter.filter(root,source,true,name -> name.startsWith("proxy"),name -> name.equals("proxy"));
        assertNull(root.getChild("proxy-denied")); assertNotNull(root.getChild("ordinary"));
        assertNotNull(root.getChild("proxy").getChild("allowed")); assertNull(root.getChild("proxy").getChild("denied"));
    }
    @Test void inspectorCanSeeInformationButBuiltinServerIsStillDisabled() {
        var root=tree("plugins","bukkit:help","server","velocity:server","passport","서버","velocity:callback");
        CommandTreeFilter.filter(root,source,true,name -> false,name -> false);
        assertEquals(Set.of("plugins","bukkit:help","passport","서버","velocity:callback"),names(root));
    }
    @Test void velocityInformationSubcommandsAreRemovedButExplicitReloadPermissionIsRespected() {
        var root=tree(); root.addChild(LiteralArgumentBuilder.<CommandSource>literal("velocity")
            .then(LiteralArgumentBuilder.<CommandSource>literal("plugins"))
            .then(LiteralArgumentBuilder.<CommandSource>literal("info"))
            .then(LiteralArgumentBuilder.<CommandSource>literal("reload").requires(ignored -> true)).build());
        CommandTreeFilter.filter(root,source,false,name -> true,name -> true);
        assertEquals(Set.of("reload"),names(root.getChild("velocity")));
        var inaccessible=tree(); inaccessible.addChild(LiteralArgumentBuilder.<CommandSource>literal("velocity")
            .then(LiteralArgumentBuilder.<CommandSource>literal("info"))
            .then(LiteralArgumentBuilder.<CommandSource>literal("reload").requires(ignored -> false)).build());
        CommandTreeFilter.filter(inaccessible,source,false,name -> true,name -> true);
        assertNull(inaccessible.getChild("velocity"));
    }
    @Test void informationCommandsAreRejectedBeforeTheyCanReachPaper() throws Exception {
        var owner=new AdmissionEventsTest(); var fixture=owner.new Fixture(); var player=fixture.player("lobby");
        for(String command:List.of("plugins","bukkit:pl","version Passport","minecraft:help","velocity plugins","velocity dump")) {
            var event=new CommandExecuteEvent(player.player,command); fixture.plugin.blockBuiltin(event);
            assertFalse(event.getResult().isAllowed(),command);
        }
        for(String command:List.of("help","?","도움말","tpa Test","home","back","lobby","서버 야생")) {
            var event=new CommandExecuteEvent(player.player,command); fixture.plugin.blockBuiltin(event);
            assertTrue(event.getResult().isAllowed(),command);
        }
    }
    @Test void inspectionPermissionOnlyRemovesThePresentationBlockAndNeverEnablesBuiltinServer() throws Exception {
        var owner=new AdmissionEventsTest(); var fixture=owner.new Fixture(); var player=fixture.player("lobby");
        player.permission=Tristate.TRUE;
        var inspect=new CommandExecuteEvent(player.player,"plugins"); fixture.plugin.blockBuiltin(inspect); assertTrue(inspect.getResult().isAllowed());
        var builtin=new CommandExecuteEvent(player.player,"server secret"); fixture.plugin.blockBuiltin(builtin); assertFalse(builtin.getResult().isAllowed());
        @SuppressWarnings("unchecked") List<String> commands=(List<String>)AdmissionEventsTest.invoke(fixture.plugin,"availablePassportCommands",player.player);
        assertFalse(commands.contains("tp")); assertFalse(commands.contains("announce"));
    }
    @Test void helpUsesTheSamePermissionListAsSuggestionsAndPreservesClickableActions() throws Exception {
        var owner=new AdmissionEventsTest(); var fixture=owner.new Fixture(); var player=fixture.player("lobby");
        AdmissionEventsTest.invoke(fixture.plugin,"help",player.player);
        String text=player.messages.toString();
        assertTrue(text.contains("/서버")); assertTrue(text.contains("/passport status"));
        assertFalse(text.contains("/passport announce")); assertFalse(text.contains("/passport adminweb"));
        assertTrue(player.messages.stream().anyMatch(component -> component.clickEvent()!=null));
    }
    @Test void actualPassportCompletionDoesNotOfferAdminCommandsOrPlayerArgumentsWithoutCentralAuthority() throws Exception {
        var owner=new AdmissionEventsTest(); var fixture=owner.new Fixture(); var player=fixture.player("lobby");
        Class<?> type=Class.forName(PassportVelocity.class.getName()+"$PassportCommand");
        var constructor=type.getDeclaredConstructor(PassportVelocity.class); constructor.setAccessible(true);
        SimpleCommand command=(SimpleCommand)constructor.newInstance(fixture.plugin);
        java.util.function.Function<String[],SimpleCommand.Invocation> invocation=args -> AdmissionEventsTest.stub(SimpleCommand.Invocation.class,(method,parameters) -> switch(method.getName()) {
            case "source" -> player.player; case "arguments" -> args; case "alias" -> "passport"; default -> null;
        });
        assertTrue(command.suggest(invocation.apply(new String[0])).contains("help"));
        assertFalse(command.suggest(invocation.apply(new String[0])).contains("tp"));
        assertTrue(command.suggest(invocation.apply(new String[]{"tp",""})).isEmpty());
        fixture.setPolicy(fixture.policy(player,true));
        assertTrue(command.suggest(invocation.apply(new String[0])).containsAll(List.of("player","tp","announce","adminweb")));
        player.permission=Tristate.FALSE;
        assertFalse(command.suggest(invocation.apply(new String[0])).contains("announce"));
        assertTrue(command.suggest(invocation.apply(new String[]{"player",""})).isEmpty());
    }
    static Set<String> names(CommandNode<?> parent) {
        Set<String> result=new HashSet<>(); parent.getChildren().forEach(child -> result.add(child.getName())); return result;
    }
}
