package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import io.github.underconnor.passport.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual proxy event receiver and existing command/admission path without live accounts. */
class ProxyCommandEventsTest {
    private static final String SECRET="secret-test-key-never-used-for-production";
    private static final MinecraftChannelIdentifier CHANNEL=MinecraftChannelIdentifier.from(ProxyCommandMessage.CHANNEL);
    private final AdmissionEventsTest.Fixture f;
    private final AdmissionEventsTest.Person player;
    ProxyCommandEventsTest() throws Exception {
        f=new AdmissionEventsTest().new Fixture(); player=f.player("lobby");
        set(f.session(player),"playReady",true);
    }
    private ProxyCommandMessage request(String command) {
        return ProxyCommandMessage.request(player.uuid,"lobby",command,Instant.now());
    }
    private PluginMessageEvent event(byte[] payload) {
        return new PluginMessageEvent(player.player.getCurrentServer().orElseThrow(),player.player,CHANNEL,payload);
    }
    private void receive(ProxyCommandMessage message) { receive(event(message.encode(SECRET))); }
    private void receive(PluginMessageEvent event) {
        f.plugin.proxyCommand(event);
        assertEquals(PluginMessageEvent.ForwardResult.handled(),event.getResult());
    }
    @Test void validBackendClickUsesPlayerHelpAndDuplicateMessageCannotRunAgain() {
        var click=request("passport help"); receive(click);
        int count=player.messages.size(); assertTrue(count>0);
        receive(click); assertEquals(count,player.messages.size());
    }
    @Test void clientOriginMessageIsHandledWithoutExecutionOrForwarding() {
        receive(new PluginMessageEvent(player.player,player.player.getCurrentServer().orElseThrow(),CHANNEL,request("passport help").encode(SECRET)));
        assertTrue(player.messages.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"malformed","forged","expired","wrong-actor","wrong-server"})
    void invalidMessagesCannotRunCommands(String condition) {
        byte[] payload=switch(condition) {
            case "malformed" -> new byte[]{1,2};
            case "forged" -> request("passport help").encode("another-key-that-is-not-the-backend-key");
            case "expired" -> ProxyCommandMessage.request(player.uuid,"lobby","passport help",Instant.now().minusSeconds(30)).encode(SECRET);
            case "wrong-actor" -> ProxyCommandMessage.request(UUID.randomUUID(),"lobby","passport help",Instant.now()).encode(SECRET);
            default -> ProxyCommandMessage.request(player.uuid,"build","passport help",Instant.now()).encode(SECRET);
        };
        receive(event(payload)); assertTrue(player.messages.isEmpty());
    }
    @Test void anotherBackendConnectionCannotImpersonateThePlayersCurrentConnection() {
        var backend=stub(ServerConnection.class,(method,args) -> switch(method.getName()) {
            case "getPlayer" -> player.player;
            case "getServerInfo" -> f.servers.get("lobby").getServerInfo();
            default -> null;
        });
        receive(new PluginMessageEvent(backend,player.player,CHANNEL,request("passport help").encode(SECRET)));
        assertTrue(player.messages.isEmpty());
    }
    @Test void matchingBackendMustAlsoBelongToTheTargetPlayer() throws Exception {
        var other=f.player("lobby");
        receive(new PluginMessageEvent(other.player.getCurrentServer().orElseThrow(),player.player,CHANNEL,request("passport help").encode(SECRET)));
        assertTrue(player.messages.isEmpty()); assertTrue(other.messages.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"not-ready","not-play-ready","inactive","replaced-session"})
    void onlyTheCurrentReadySessionCanRelay(String condition) throws Exception {
        switch(condition) {
            case "not-ready" -> set(f.plugin,"ready",false);
            case "not-play-ready" -> set(f.session(player),"playReady",false);
            case "inactive" -> player.active=false;
            default -> f.player("lobby",player.uuid);
        }
        receive(request("passport help")); assertTrue(player.messages.isEmpty());
    }
    @Test void serverClickFetchesFreshPolicyThenUsesExistingAdmissionAndConnection() throws Exception {
        var reads=new AtomicInteger();
        f.source=uuid -> { reads.incrementAndGet(); return CompletableFuture.completedFuture(f.responses.get(uuid)); };
        receive(request("passport server build"));
        assertEquals(1,reads.get()); assertEquals(1,player.connectRequests);
        assertEquals("build",f.queue.reservation(f.key(player)).orElseThrow().server());
    }
    @Test void fullServerClickQueuesThePlayerAndNeverBypassesCapacity() throws Exception {
        f.player("build"); receive(request("passport server build"));
        assertEquals(0,player.connectRequests);
        assertEquals("build",f.queue.position(f.key(player)).orElseThrow().ticket().server());
        assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void newlyRevokedServerScopeIsNotGrantedByOldCachedPolicy() throws Exception {
        Instant now=Instant.now();
        f.source=uuid -> CompletableFuture.completedFuture(new Policy(uuid,"active",Set.of("lobby"),"","",100,now,now.plusSeconds(60)));
        receive(request("passport server build"));
        assertEquals(0,player.connectRequests); assertFalse(player.messages.isEmpty());
        assertTrue(f.queue.position(f.key(player)).isEmpty()); assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void policyServiceFailureDoesNotUseStaleAllowedScope() throws Exception {
        f.source=uuid -> CompletableFuture.failedFuture(new IllegalStateException("offline"));
        receive(request("passport server build"));
        assertEquals(0,player.connectRequests); assertFalse(player.messages.isEmpty());
        assertTrue(f.queue.position(f.key(player)).isEmpty()); assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void aReplacedSessionWhilePolicyIsPendingCannotConnectLater() throws Exception {
        CompletableFuture<Policy> pending=new CompletableFuture<>(); f.source=uuid -> pending;
        receive(request("passport server build"));
        f.player("lobby",player.uuid); pending.complete(f.policy(player,false));
        assertEquals(0,player.connectRequests); assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void bridgeSharesTheTypedCommandsClickRateLimit() {
        var reads=new AtomicInteger();
        f.source=uuid -> { reads.incrementAndGet(); return CompletableFuture.completedFuture(f.responses.get(uuid)); };
        receive(request("passport server")); receive(request("passport server"));
        assertEquals(1,reads.get());
    }
    @Test void shutdownClearsRelayReplayStateAndUnregistersThePrivateChannel() throws Exception {
        List<ChannelIdentifier> removed=new ArrayList<>();
        var registrar=stub(ChannelRegistrar.class,(method,args) -> {
            if(method.getName().equals("unregister")) Collections.addAll(removed,(ChannelIdentifier[])args[0]);
            return null;
        });
        var proxy=stub(ProxyServer.class,(method,args) -> method.getName().equals("getChannelRegistrar") ? registrar : null);
        var plugin=new PassportVelocity(proxy,stub(org.slf4j.Logger.class,(method,args) -> null));
        var replay=(ProxyCommandReplayGuard)field(plugin,"proxyCommands");
        var click=request("passport help"); assertTrue(replay.claim(click,Instant.now()));
        plugin.shutdown(new ProxyShutdownEvent());
        assertTrue(removed.contains(CHANNEL)); assertTrue(replay.claim(click,Instant.now()));
        assertEquals(false,field(plugin,"ready"));
    }
    @Test void unrelatedPluginChannelRemainsUntouched() {
        var other=new PluginMessageEvent(player.player.getCurrentServer().orElseThrow(),player.player,
            MinecraftChannelIdentifier.from("other:command"),request("passport help").encode(SECRET));
        var initial=other.getResult(); f.plugin.proxyCommand(other);
        assertEquals(initial,other.getResult()); assertTrue(player.messages.isEmpty());
    }
}
