package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.permission.Tristate;
import io.github.underconnor.passport.core.Policy;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.*;
import static io.github.underconnor.passport.velocity.NetworkRosterCommandTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WhisperCommandTest {
    @Test void ordinaryPlayersWhisperByRealNameAcrossBackendsAndMessageFormattingStaysLiteral() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),target=person(f,"build","Target","정지원");
        var command=whisper(f.plugin);
        assertTrue(command.hasPermission(invocation(sender.player,"정지원","hello")));
        command.execute(invocation(sender.player,"정지원","<red>안녕","§a테스트"));
        assertEquals("[귓속말] Sender → 나: <red>안녕 §a테스트",text(target.messages.getLast()));
        assertEquals("[귓속말] 나 → Target: <red>안녕 §a테스트",text(sender.messages.getLast()));
        Component body=target.messages.getLast().children().getLast();
        assertEquals("<red>안녕 §a테스트",((TextComponent)body).content()); assertEquals(NamedTextColor.WHITE,body.color());
        assertNull(body.clickEvent()); assertNull(body.hoverEvent()); assertEquals("lobby",sender.server); assertEquals("build",target.server);
    }
    @Test void ignWinsOverSomeoneElsesRealNameAndDuplicateRealNamesShowIgnChoicesWithoutSending() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),first=person(f,"build","First","정지원"),second=person(f,"limbo","Second","정지원");
        var command=whisper(f.plugin); command.execute(invocation(sender.player,"정지원","안녕"));
        assertTrue(first.messages.isEmpty()); assertTrue(second.messages.isEmpty());
        assertTrue(text(sender.messages.getFirst()).contains("같은 이름"));
        assertTrue(sender.messages.stream().anyMatch(component -> net.kyori.adventure.text.event.ClickEvent.suggestCommand("/msg First ").equals(component.clickEvent())));
        named(f,second,"First","active",false); cooldown(f,sender);
        command.execute(invocation(sender.player,"fIrSt","정확한","대상"));
        assertEquals("[귓속말] Sender → 나: 정확한 대상",text(first.messages.getLast())); assertTrue(second.messages.isEmpty());
    }
    @Test void expiredRevokedSuspendedAndDisconnectedTargetsNeverExposeRealNames() throws Exception {
        for(String status:List.of("expired","revoked","suspended","stale","unlinked","pending","offline","replaced")) {
            var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),target=person(f,"build","Target","정지원");
            if(status.equals("offline")) target.active=false;
            else if(status.equals("replaced")) {
                var fresh=person(f,"limbo",target.uuid,"Fresh","새 이름"); named(f,fresh,"새 이름","active",false);
            } else named(f,target,"정지원",status.equals("expired") ? "active" : status,status.equals("expired"));
            var command=whisper(f.plugin);
            assertFalse(command.suggest(invocation(sender.player,"")).contains("정지원"),status);
            command.execute(invocation(sender.player,"정지원","안녕"));
            assertTrue(target.messages.isEmpty(),status); assertTrue(text(sender.messages.getLast()).contains("찾을 수 없습니다"),status);
        }
    }
    @Test void senderWithoutCurrentSchoolIdentityCanUseIgnButCannotDiscoverOrResolveRealNames() throws Exception {
        for(String status:List.of("unlinked","expired","revoked")) {
            var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"limbo","Sender","본인"),target=person(f,"build","Target","정지원");
            named(f,sender,"본인",status.equals("expired") ? "active" : status,status.equals("expired"));
            var command=whisper(f.plugin); var suggestions=command.suggest(invocation(sender.player,""));
            assertTrue(suggestions.contains("Target")); assertFalse(suggestions.contains("정지원"));
            command.execute(invocation(sender.player,"정지원","안녕")); assertTrue(target.messages.isEmpty());
            cooldown(f,sender); command.execute(invocation(sender.player,"Target","안녕"));
            assertEquals("[귓속말] Sender → 나: 안녕",text(target.messages.getLast()));
        }
    }
    @Test void completionUsesLiveSessionNamesWithoutAdminOrApiLookupAndOmitsMessageArguments() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var sender=person(f,"lobby","Sender","본인"); person(f,"build","Target","정지원");
        f.source=uuid -> { fail("Whispers must not query API or request administrator access"); return null; };
        var command=whisper(f.plugin);
        assertEquals(List.of("정지원"),command.suggest(invocation(sender.player,"정")));
        assertEquals(List.of("Target"),command.suggest(invocation(sender.player,"ta")));
        assertFalse(command.suggest(invocation(sender.player,"")).contains("Sender"));
        assertTrue(command.suggest(invocation(sender.player,"Target","")).isEmpty());
        command.execute(invocation(sender.player,"정지원","내용"));
        assertTrue(text(sender.messages.getLast()).contains("나 → Target"));
    }
    @Test void explicitDenyIsHiddenAndRejectedBeforeBackendFallbackAndStaleSenderCannotSend() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),target=person(f,"build","Target","정지원");
        sender.permission=Tristate.FALSE; var command=whisper(f.plugin);
        assertFalse(command.hasPermission(invocation(sender.player,"Target","hello"))); assertTrue(command.suggest(invocation(sender.player,"")).isEmpty());
        for(String alias:List.of("msg","tell","w","귓")) {
            var event=new CommandExecuteEvent(sender.player,alias+" Target hello"); f.plugin.blockBuiltin(event); assertFalse(event.getResult().isAllowed());
        }
        command.execute(invocation(sender.player,"Target","hello")); assertTrue(target.messages.isEmpty());
        sender.permission=Tristate.UNDEFINED; person(f,"limbo",sender.uuid,"Fresh","새 이름");
        assertFalse(command.hasPermission(invocation(sender.player,"Target","hello"))); command.execute(invocation(sender.player,"Target","hello")); assertTrue(target.messages.isEmpty());
    }
    @Test void offlineSelfBadInputAndSpamNeverDeliverAndReconnectHasIndependentCooldown() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),target=person(f,"build","Target","정지원"); var command=whisper(f.plugin);
        command.execute(invocation(sender.player,"Sender","hello")); assertTrue(text(sender.messages.getLast()).contains("자신"));
        cooldown(f,sender); command.execute(invocation(sender.player,"Missing","hello")); assertTrue(text(sender.messages.getLast()).contains("찾을 수 없습니다"));
        for(String[] input:List.of(new String[]{"Target"},new String[]{"Target"," "},new String[]{"Target","hello\nworld"},new String[]{"Target","x".repeat(301)})) {
            cooldown(f,sender); command.execute(invocation(sender.player,input)); assertTrue(target.messages.isEmpty());
        }
        cooldown(f,sender); command.execute(invocation(sender.player,"Target","first")); int sent=target.messages.size();
        command.execute(invocation(sender.player,"Target","second")); assertEquals(sent,target.messages.size()); assertTrue(text(sender.messages.getLast()).contains("잠시 후"));
        var fresh=person(f,"lobby",sender.uuid,"Sender","본인"); command.execute(invocation(fresh.player,"Target","after reconnect"));
        assertEquals(sent+1,target.messages.size()); assertTrue(text(target.messages.getLast()).endsWith("after reconnect"));
    }
    @Test void consoleAndNotYetPlayingSessionsCannotSend() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),target=person(f,"build","Target","정지원"); var command=whisper(f.plugin);
        var console=stub(CommandSource.class,(method,args) -> null); assertFalse(command.hasPermission(invocation(console,"Target","hello")));
        set(f.session(sender),"playReady",false); assertTrue(command.hasPermission(invocation(sender.player,"Target","hello")));
        assertTrue(command.suggest(invocation(sender.player,"")).isEmpty());
        command.execute(invocation(sender.player,"Target","hello")); assertTrue(target.messages.isEmpty());
    }
    @Test void sessionRemovedAfterPermissionPreflightMakesCompletionAndExecutionSafe() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person sender=person(f,"lobby","Sender","본인"),target=person(f,"build","Target","정지원"); var command=whisper(f.plugin);
        assertTrue(command.hasPermission(invocation(sender.player,"Target","hello")));
        @SuppressWarnings("unchecked") Map<UUID,Object> sessions=(Map<UUID,Object>)field(f.plugin,"sessions"); sessions.remove(sender.uuid);
        assertDoesNotThrow(() -> assertTrue(command.suggest(invocation(sender.player,"")).isEmpty()));
        assertDoesNotThrow(() -> command.execute(invocation(sender.player,"Target","hello"))); assertTrue(target.messages.isEmpty());
    }
    static SimpleCommand whisper(PassportVelocity plugin) throws Exception {
        var type=Arrays.stream(PassportVelocity.class.getDeclaredClasses()).filter(candidate -> candidate.getSimpleName().equals("WhisperCommand")).findFirst().orElseThrow();
        var constructor=type.getDeclaredConstructor(PassportVelocity.class); constructor.setAccessible(true); return (SimpleCommand)constructor.newInstance(plugin);
    }
    static Person person(AdmissionEventsTest.Fixture f,String server,String ign,String real) throws Exception { return person(f,server,UUID.randomUUID(),ign,real); }
    static Person person(AdmissionEventsTest.Fixture f,String server,UUID uuid,String ign,String real) throws Exception {
        var person=f.player(server,uuid); person.ign=ign; set(f.session(person),"playReady",true); named(f,person,real,"active",false); return person;
    }
    static void named(AdmissionEventsTest.Fixture f,Person person,String real,String status,boolean expired) {
        Instant now=Instant.now(); f.setPolicy(new Policy(person.uuid,status,Set.of("lobby","build"),"",real,++f.policyVersion,
            now.minusSeconds(expired ? 61 : 0),now.plusSeconds(expired ? -1 : 60),false,null,false,Map.of()));
    }
    static void cooldown(AdmissionEventsTest.Fixture f,Person sender) throws Exception { ((AtomicLong)field(f.session(sender),"lastWhisper")).set(Long.MIN_VALUE); }
}
