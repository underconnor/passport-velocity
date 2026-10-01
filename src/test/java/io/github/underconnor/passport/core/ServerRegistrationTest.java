package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerRegistrationTest {
    @Test void discoveryExcludesWaitingServerAndSendsOnlyLogicalNames() {
        var servers=ServerRegistration.backends(List.of("survival","passport-limbo","lobby"),"passport-limbo");
        assertEquals(List.of(new ServerRegistration("lobby","lobby"),new ServerRegistration("survival","survival")),servers);
        var body=ServerRegistration.payload("velocity",servers);
        assertEquals(Set.of("source","servers"),body.keySet()); assertEquals("velocity",body.get("source").getAsString());
        body.getAsJsonArray("servers").forEach(item -> assertEquals(Set.of("id","label"),item.getAsJsonObject().keySet()));
    }
    @Test void rejectsInvalidIdsLabelsDuplicatesAndOversizedBatchWithoutLeakingValues() {
        for(String id:List.of("Lobby","lobby.example:25565","../private","a".repeat(65),"")) {
            var error=assertThrows(IllegalArgumentException.class,()->new ServerRegistration(id,"label"));
            assertEquals("Invalid server ID",error.getMessage());
        }
        for(String label:List.of(" ","label\nprivate","label\u007fprivate","a".repeat(81)))
            assertThrows(IllegalArgumentException.class,()->new ServerRegistration("lobby",label));
        var entry=new ServerRegistration("lobby","로비");
        assertThrows(IllegalArgumentException.class,()->ServerRegistration.payload("admin",List.of(entry)));
        assertThrows(IllegalArgumentException.class,()->ServerRegistration.payload("paper",List.of(entry,entry)));
        assertThrows(IllegalArgumentException.class,()->ServerRegistration.payload("velocity",IntStream.range(0,65).mapToObj(i->new ServerRegistration("server"+i,"label")).toList()));
    }
    @Test void acceptsBoundarySizeAndPaperLabelButDoesNotAddAccessSettings() {
        var label=new ServerRegistration("a"+"b".repeat(63)," "+"가".repeat(80)+" ");
        assertEquals(80,label.label().length());
        assertEquals(64,ServerRegistration.payload("velocity",IntStream.range(0,64).mapToObj(i->new ServerRegistration("server"+i,"label")).toList()).getAsJsonArray("servers").size());
        assertEquals(0,ServerRegistration.payload("velocity",List.of()).getAsJsonArray("servers").size());
        assertEquals(Set.of("id","label"),ServerRegistration.payload("paper",List.of(label)).getAsJsonArray("servers").get(0).getAsJsonObject().keySet());
    }
}
