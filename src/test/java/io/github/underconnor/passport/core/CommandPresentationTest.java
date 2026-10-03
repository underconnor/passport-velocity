package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CommandPresentationTest {
    final UUID uuid=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-03T00:00:00Z");
    Policy policy(boolean admin,String status,Set<String> servers) {
        return new Policy(uuid,status,servers,"","",1,now,now.plusSeconds(60),false,null,admin,Map.of());
    }
    @Test void knownInformationAliasesAreMatchedExactlyIncludingNamespacesAndArguments() {
        for(String command:List.of("plugins","PL","bukkit:plugins 2","bukkit:pl","version SomePlugin","ver","about","icanhasbukkit",
                "bukkit:help SomePlugin","minecraft:help","bukkit:?","velocity","velocity plugins","velocity info","velocity dump","velocity heap","velocity unknown"))
            assertTrue(CommandPresentation.informationCommand(command),command);
    }
    @Test void ordinaryHelpAndUnrelatedOrSimilarlyNamedCommandsRemainUsable() {
        for(String command:List.of("help","?","도움말","tpa Test","home","back","lobby","서버 야생","adminhome","pluginshelp","versioning",
                "essentials:home","velocity reload","velocity:callback abc"))
            assertFalse(CommandPresentation.informationCommand(command),command);
    }
    @Test void ordinaryUsersSeeOnlyUsablePassportCommands() {
        List<String> commands=CommandPresentation.passportCommands(policy(false,"active",Set.of("lobby")),uuid,true,false,false,now);
        assertTrue(commands.containsAll(List.of("help","server","status","web","queue","link")));
        assertTrue(Collections.disjoint(commands,List.of("tp","announce","player","adminweb")));
    }
    @Test void unknownOrRevokedServerScopesDoNotAppearAsUsableServerTransfers() {
        for(Policy policy:Arrays.asList(null,policy(false,"revoked",Set.of()),policy(false,"active",Set.of()))) {
            List<String> commands=CommandPresentation.passportCommands(policy,uuid,true,false,false,now);
            assertFalse(commands.contains("server")); assertTrue(commands.contains("help")); assertTrue(commands.contains("web"));
        }
    }
    @Test void adminVisibilityMatchesCurrentIdentityLeaseAndExplicitLocalDeny() {
        Policy admin=policy(true,"active",Set.of("lobby"));
        assertTrue(CommandPresentation.passportCommands(admin,uuid,true,false,false,now).containsAll(List.of("tp","player","adminweb","announce")));
        assertFalse(CommandPresentation.administrator(admin,uuid,true,true,now));
        assertFalse(CommandPresentation.administrator(admin,UUID.randomUUID(),true,false,now));
        assertFalse(CommandPresentation.administrator(admin,uuid,false,false,now));
        assertFalse(CommandPresentation.administrator(admin,uuid,true,false,admin.expiresAt()));
    }
    @Test void adminInformationCommandsMayRemainUsableWithoutGameScopeButTeleportDoesNot() {
        List<String> commands=CommandPresentation.passportCommands(policy(true,"revoked",Set.of()),uuid,true,false,false,now);
        assertTrue(commands.containsAll(List.of("player","adminweb","announce"))); assertFalse(commands.contains("tp"));
    }
    @Test void consoleHelpDoesNotAdvertisePlayerOnlyOperations() {
        assertEquals(List.of("help","player","adminweb","announce"),CommandPresentation.passportCommands(null,null,false,false,true,now));
        assertTrue(CommandPresentation.passportCommands(null,uuid,false,false,false,now).isEmpty());
    }
}
