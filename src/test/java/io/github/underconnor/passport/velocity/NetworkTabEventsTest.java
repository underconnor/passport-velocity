package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import org.junit.jupiter.api.Test;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.*;
import static io.github.underconnor.passport.velocity.NetworkRosterCommandTest.text;
import static org.junit.jupiter.api.Assertions.*;

class NetworkTabEventsTest {
    @Test void everyBackendReceivesTheNetworkCountIncludingWaitingAndConnectingPlayers() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person lobby=f.player("lobby"),build=f.player("build"),waiting=f.player("limbo");
        f.plugin.postConnected(new ServerPostConnectEvent(lobby.player,null));
        f.plugin.postConnected(new ServerPostConnectEvent(build.player,null));
        f.plugin.postConnected(new ServerPostConnectEvent(waiting.player,null));
        var connecting=f.player(null);
        for(var person:java.util.List.of(lobby,build,waiting)) {
            assertEquals("현재 접속 중 · 4명",text(person.tabFooters.getLast()));
            assertEquals("숭실대학교 AI소프트웨어학부 소모임 오버월드\noverworld.flyjung.kr",text(person.tabHeaders.getLast()));
        }
        assertTrue(connecting.tabFooters.isEmpty());
    }
    @Test void disconnectCountIsCorrectBeforeVelocityRemovesThePlayerAndUnchangedTicksDoNotSend() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); Person viewer=f.player("lobby"),leaving=f.player("build");
        f.plugin.postConnected(new ServerPostConnectEvent(viewer.player,null));
        f.plugin.postConnected(new ServerPostConnectEvent(leaving.player,null));
        int before=viewer.tabFooters.size(); invoke(f.plugin,"updateNetworkTab",null,null);
        assertEquals(before,viewer.tabFooters.size());
        // Velocity may still report an active player while the disconnect event is being processed.
        f.plugin.disconnect(new DisconnectEvent(leaving.player,DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        assertEquals("현재 접속 중 · 1명",text(viewer.tabFooters.getLast()));
        leaving.active=false; invoke(f.plugin,"tick");
        assertEquals(before+1,viewer.tabFooters.size());
    }
    @Test void backendTransferForcesPresentationAndStaleSessionCannotReceiveHeaders() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=f.player("lobby");
        f.plugin.postConnected(new ServerPostConnectEvent(player.player,null)); int before=player.tabFooters.size();
        player.server="build"; f.plugin.postConnected(new ServerPostConnectEvent(player.player,f.servers.get("lobby")));
        assertEquals(before+1,player.tabFooters.size());
        var replacement=f.player("limbo",player.uuid); f.plugin.postConnected(new ServerPostConnectEvent(replacement.player,null));
        before=player.tabFooters.size(); invoke(f.plugin,"updateNetworkTab",null,null);
        assertEquals(before,player.tabFooters.size()); assertEquals("현재 접속 중 · 1명",text(replacement.tabFooters.getLast()));
        f.plugin.disconnect(new DisconnectEvent(player.player,DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        assertEquals("현재 접속 중 · 1명",text(replacement.tabFooters.getLast()));
    }
    @Test void aStoppedTickerDoesNothing() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var viewer=f.player("lobby"); f.plugin.postConnected(new ServerPostConnectEvent(viewer.player,null));
        // A stopped configuration is sufficient to verify that scheduled TAB work cannot keep running.
        set(f.plugin,"ready",false); int before=viewer.tabFooters.size(); invoke(f.plugin,"tick");
        assertEquals(before,viewer.tabFooters.size());
    }
    @Test void shutdownClearsTheOwnedHeaderAndFooter() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var viewer=f.player("lobby"); f.plugin.postConnected(new ServerPostConnectEvent(viewer.player,null));
        f.plugin.shutdown(new com.velocitypowered.api.event.proxy.ProxyShutdownEvent());
        assertEquals("",text(viewer.tabHeaders.getLast())); assertEquals("",text(viewer.tabFooters.getLast()));
        int before=viewer.tabFooters.size(); invoke(f.plugin,"updateNetworkTab",null,null); assertEquals(before,viewer.tabFooters.size());
    }
    @Test void aLateBackendHeaderIsRepairedEvenWhenThePlayerCountDoesNotChange() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var viewer=f.player("limbo"); f.plugin.postConnected(new ServerPostConnectEvent(viewer.player,null));
        int before=viewer.tabFooters.size(); viewer.tabHeader=net.kyori.adventure.text.Component.text("old static header");
        viewer.tabFooter=net.kyori.adventure.text.Component.text("overworld.flyjung.kr");
        invoke(f.plugin,"updateNetworkTab",null,null);
        assertEquals(before+1,viewer.tabFooters.size()); assertEquals("현재 접속 중 · 1명",text(viewer.tabFooters.getLast()));
        before=viewer.tabFooters.size(); invoke(f.plugin,"updateNetworkTab",null,null); assertEquals(before,viewer.tabFooters.size());
    }
    @Test void rawBackendPacketsMissingFromTheApiCacheStillReceiveABoundedResynchronization() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var viewer=f.player("limbo"); f.plugin.postConnected(new ServerPostConnectEvent(viewer.player,null));
        int before=viewer.tabFooters.size();
        // A raw backend header may only change the client, leaving proxy-side getters unchanged.
        set(f.session(viewer),"nextTabRefresh",System.nanoTime()-1); invoke(f.plugin,"updateNetworkTab",null,null);
        assertEquals(before+1,viewer.tabFooters.size()); assertEquals("현재 접속 중 · 1명",text(viewer.tabFooters.getLast()));
        long next=(long)field(f.session(viewer),"nextTabRefresh"); assertTrue(next>System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8));
        invoke(f.plugin,"updateNetworkTab",null,null); assertEquals(before+1,viewer.tabFooters.size());
    }
}
