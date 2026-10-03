package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.proxy.Player;
import io.github.underconnor.passport.core.*;
import net.kyori.adventure.text.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRosterCommandTest {
    @Test void consoleCommandCountsLiveProxyPlayersAndShowsTheConfiguredLabels() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); f.player("lobby"); f.player("build"); var gone=f.player("build"); gone.active=false;
        var output=new ArrayList<Component>();
        var console=stub(CommandSource.class,(method,args) -> { if(method.getName().equals("sendMessage")) output.add((Component)args[args.length-1]); return null; });
        command(f.plugin).execute(invocation(console,"list"));
        String text=String.join("\n",output.stream().map(NetworkRosterCommandTest::text).toList());
        assertTrue(text.contains("접속 중 · 2명")); assertTrue(text.contains(" · 로비")); assertTrue(text.contains(" · 건축"));
        assertFalse(text.contains("ssu_")); assertTrue(command(f.plugin).suggest(invocation(console,"li")).contains("list"));
    }
    @Test void playerGetsEveryOnlineIgnButOnlyTheirAllowedServerLocations() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var viewer=f.player("lobby"); f.player("build");
        f.setPolicy(new Policy(viewer.uuid,"active",Set.of("lobby"),"","",500,Instant.now(),Instant.now().plusSeconds(60),false,null,false,Map.of("lobby","로비")));
        command(f.plugin).execute(invocation(viewer.player,"list"));
        String output=String.join("\n",viewer.messages.stream().map(NetworkRosterCommandTest::text).toList());
        assertTrue(output.contains("접속 중 · 2명")); assertTrue(output.contains(" · 로비")); assertTrue(output.contains(" · 비공개 서버")); assertFalse(output.contains("건축"));
    }
    @Test void unverifiedAndFailedPolicyRefreshDoNotExposeTheRoster() throws Exception {
        for(boolean failure:List.of(false,true)) {
            var f=new AdmissionEventsTest().new Fixture(); var viewer=f.player("limbo"); f.player("build");
            if(failure) f.source=uuid -> CompletableFuture.failedFuture(new IllegalStateException("offline"));
            else f.setPolicy(new Policy(viewer.uuid,"unlinked",Set.of(),"","",500,Instant.now(),Instant.now().plusSeconds(60)));
            command(f.plugin).execute(invocation(viewer.player,"list"));
            String output=String.join("\n",viewer.messages.stream().map(NetworkRosterCommandTest::text).toList());
            assertTrue(output.contains("확인할 수 없습니다")); assertFalse(output.contains("TestPlayer"));
        }
    }
    static SimpleCommand command(PassportVelocity plugin) throws Exception {
        var type=Arrays.stream(PassportVelocity.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals("PassportCommand")).findFirst().orElseThrow();
        Constructor<?> constructor=type.getDeclaredConstructor(PassportVelocity.class); constructor.setAccessible(true); return (SimpleCommand)constructor.newInstance(plugin);
    }
    static SimpleCommand.Invocation invocation(CommandSource source,String... arguments) {
        return stub(SimpleCommand.Invocation.class,(method,args) -> switch(method.getName()) { case "source" -> source; case "arguments" -> arguments; default -> null; });
    }
    static String text(Component component) { return (component instanceof TextComponent t ? t.content() : "")+component.children().stream().map(NetworkRosterCommandTest::text).collect(java.util.stream.Collectors.joining()); }
}
