package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import io.github.underconnor.passport.core.*;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class QueueRecoveryEventsTest {
    @Test void queuedPlayerRetainsPositionAcrossRestartFailuresThenEntersWithoutTypingAgain() throws Exception {
        var f=new AdmissionEventsTest().new Fixture();
        var occupant=f.player("lobby"); var first=f.player("limbo"); var second=f.player("build");
        await(f.plugin.beforeConnect(f.preconnect(first,"lobby")));
        await(f.plugin.beforeConnect(f.preconnect(second,"lobby")));
        var ticket=f.queue.position(f.key(first)).orElseThrow().ticket();
        set(f.session(first),"playReady",true); occupant.server=null;
        for(int attempt=0;attempt<8;attempt++) {
            skipCooldown(f,first); first.connection=new CompletableFuture<>();
            invoke(f.plugin,"queueTick",f.session(first),System.nanoTime());
            assertEquals(attempt+1,first.connectRequests);
            await(f.plugin.beforeConnect(f.preconnect(first,"lobby")));
            first.connection.complete(result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED,f.servers.get("lobby")));
            assertEquals(ticket,f.queue.position(f.key(first)).orElseThrow().ticket());
            assertEquals(1,f.queue.position(f.key(first)).orElseThrow().position());
            assertEquals(2,f.queue.position(f.key(second)).orElseThrow().position());
            assertTrue(f.queue.reservation(f.key(first)).isEmpty());
            assertFalse(((QueueIntent)field(f.session(first),"queueIntent")).automaticPaused());
        }
        skipCooldown(f,first); first.connection=new CompletableFuture<>();
        invoke(f.plugin,"queueTick",f.session(first),System.nanoTime());
        await(f.plugin.beforeConnect(f.preconnect(first,"lobby")));
        first.server="lobby"; f.plugin.postConnected(new ServerPostConnectEvent(first.player,f.servers.get("limbo")));
        first.connection.complete(result(ConnectionRequestBuilder.Status.SUCCESS,f.servers.get("lobby")));
        assertEquals(9,first.connectRequests); assertTrue(f.queue.position(f.key(first)).isEmpty());
        assertTrue(f.queue.reservation(f.key(first)).isEmpty());
        assertEquals(1,f.queue.position(f.key(second)).orElseThrow().position());
    }
    @Test void cancellationDuringFailedPromotionDoesNotResurrectTheTicket() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=promoting(f);
        invoke(f.plugin,"leaveQueue",f.session(player));
        player.connection.completeExceptionally(new IllegalStateException("destination unavailable"));
        assertTrue(f.queue.position(f.key(player)).isEmpty());
        assertTrue(((QueueIntent)field(f.session(player),"queueIntent")).automaticPaused());
    }
    @Test void revokedAccessCannotRestoreAFailedPromotion() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=promoting(f);
        f.setPolicy(new Policy(player.uuid,"revoked",Set.of(),"","",100,Instant.now(),Instant.now().plusSeconds(60)));
        player.connection.completeExceptionally(new IllegalStateException("destination unavailable"));
        assertTrue(f.queue.position(f.key(player)).isEmpty()); assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void oldLoginFailureCannotRestoreAQueueForTheNewLogin() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var old=promoting(f); var fresh=f.player("limbo",old.uuid);
        old.connection.completeExceptionally(new IllegalStateException("destination unavailable"));
        assertTrue(f.queue.position(f.key(fresh)).isEmpty()); assertTrue(f.queue.reservation(f.key(fresh)).isEmpty());
    }
    @Test void cancelledConnectionByAnotherPluginIsNotForcedBackIntoTheQueue() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=promoting(f);
        player.connection.complete(result(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED,f.servers.get("lobby")));
        assertTrue(f.queue.position(f.key(player)).isEmpty()); assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void failedDestinationKeepsTheCurrentBackendInsteadOfRedirectingItAgain() throws Exception {
        for(String current:List.of("limbo","build")) {
            var f=new AdmissionEventsTest().new Fixture(); var player=f.player(current);
            var event=new KickedFromServerEvent(player.player,f.servers.get("lobby"),Component.text("restarting"),true,
                KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));
            f.plugin.kicked(event);
            assertInstanceOf(KickedFromServerEvent.Notify.class,event.getResult());
            assertEquals(current,player.server); assertTrue(player.active);
        }
    }
    @Test void actualBackendShutdownResumesLobbyRecoveryAfterAnEarlierVoluntaryQueueCancel() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=f.player("lobby");
        var intent=(QueueIntent)field(f.session(player),"queueIntent"); intent.cancel();
        var event=new KickedFromServerEvent(player.player,f.servers.get("lobby"),Component.text("restarting"),false,
            KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));
        f.plugin.kicked(event);
        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class,event.getResult());
        assertFalse(intent.automaticPaused());
    }
    private Person promoting(AdmissionEventsTest.Fixture f) throws Exception {
        var occupant=f.player("lobby"); var player=f.player("limbo");
        await(f.plugin.beforeConnect(f.preconnect(player,"lobby")));
        occupant.server=null; set(f.session(player),"playReady",true);
        invoke(f.plugin,"queueTick",f.session(player),System.nanoTime());
        assertEquals(1,player.connectRequests); return player;
    }
    private void skipCooldown(AdmissionEventsTest.Fixture f,Person person) throws Exception {
        // Backoff timing itself is covered with a controlled clock in AdmissionQueueTest.
        ((Map<?,?>)field(f.queue,"retryAfter")).clear(); set(f.session(person),"nextQueueCheck",0L);
    }
}
