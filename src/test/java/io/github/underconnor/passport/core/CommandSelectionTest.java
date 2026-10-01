package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CommandSelectionTest {
    @Test void onlyAllowedIdsAndLabelsResolveAndDuplicateLabelRequiresId() {
        Policy policy=new Policy(UUID.randomUUID(),"active",Set.of("lobby","build"),"","테스트",1,Instant.now(),Instant.now().plusSeconds(30),true,"26",false,Map.of("lobby","공용","build","공용"));
        assertEquals(List.of("lobby"),CommandSelection.servers(policy,"lobby"));
        assertEquals(List.of("build","lobby"),CommandSelection.servers(policy,"공용"));
        assertTrue(CommandSelection.servers(policy,"secret").isEmpty());
    }
    @Test void suggestionsHandleCaseKoreanDuplicatesAndResultBound() {
        assertEquals(List.of("Player"),CommandSelection.suggestions(List.of("Player","Player","Else"),"pl"));
        assertEquals(List.of("정지원"),CommandSelection.suggestions(List.of("정지원","김테스트"),"정"));
        assertTrue(CommandSelection.suggestions(List.of("bad\ncommand"),"").isEmpty());
        assertEquals(50,CommandSelection.suggestions(java.util.stream.IntStream.range(0,100).mapToObj(i -> "player"+i).toList(),"").size());
    }
    @Test void builtinAndNamespaceAreBlockedWithoutBlockingOtherCommands() {
        for(String cmd:List.of("server", " SeRvEr lobby", "velocity:server private")) assertTrue(CommandSelection.blockedBuiltin(cmd));
        for(String cmd:List.of("passport server lobby","serverinfo","other:server")) assertFalse(CommandSelection.blockedBuiltin(cmd));
    }
    @Test void announcementsAreLengthBoundedAndRejectControlCharactersButRemainLiteral() {
        assertTrue(CommandSelection.safeAnnouncement("<red>일반 텍스트</red>"));
        assertFalse(CommandSelection.safeAnnouncement("공지\n/op test"));
        assertFalse(CommandSelection.safeAnnouncement("x".repeat(301)));
        assertFalse(CommandSelection.safeAnnouncement(" "));
    }
}
