package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.github.underconnor.passport.core.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static io.github.underconnor.passport.velocity.AdmissionEventsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DepartureEventsTest {
    final String secret="secret-test-key-never-used-for-production";
    @Test void onlyCompletedCrossServerConnectionsSendTheOldServerASignedSuccess() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=f.player("lobby");
        f.plugin.connected(new ServerConnectedEvent(player.player,f.servers.get("lobby"),null));
        f.plugin.postConnected(new ServerPostConnectEvent(player.player,null));
        var begin=DepartureMessage.decode(player.packets.getFirst(),secret,Instant.now()); assertEquals("BEGIN",begin.kind());
        assertTrue(f.packets.get("lobby").isEmpty());
        var pre=f.preconnect(player,"build"); await(f.plugin.beforeConnect(pre)); assertTrue(pre.getResult().isAllowed());
        assertTrue(f.packets.get("lobby").isEmpty(),"An attempt must not announce a successful transfer");
        f.plugin.connected(new ServerConnectedEvent(player.player,f.servers.get("build"),f.servers.get("lobby")));
        var transferred=DepartureMessage.decode(f.packets.get("lobby").getFirst(),secret,Instant.now());
        assertEquals("TRANSFER",transferred.kind()); assertEquals(begin.connectionId(),transferred.connectionId()); assertEquals("lobby",transferred.serverId());
        player.server="build"; f.plugin.postConnected(new ServerPostConnectEvent(player.player,f.servers.get("lobby")));
        var next=DepartureMessage.decode(player.packets.getLast(),secret,Instant.now()); assertEquals("build",next.serverId()); assertNotEquals(begin.connectionId(),next.connectionId());
    }
    @Test void rejectedConnectionAndObsoleteSessionsNeverSendTransferSuccess() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=f.player("lobby"); f.player("build");
        f.plugin.connected(new ServerConnectedEvent(player.player,f.servers.get("lobby"),null));
        var pre=f.preconnect(player,"build"); await(f.plugin.beforeConnect(pre)); assertFalse(pre.getResult().isAllowed());
        assertTrue(f.packets.get("lobby").isEmpty());
        f.player("lobby",player.uuid);
        f.plugin.connected(new ServerConnectedEvent(player.player,f.servers.get("build"),f.servers.get("lobby")));
        assertTrue(f.packets.get("lobby").isEmpty());
    }
    @Test void clientPacketsAndBackendRepliesAreNeverForwardedToAnotherParty() throws Exception {
        var f=new AdmissionEventsTest().new Fixture(); var player=f.player("lobby");
        var channel=MinecraftChannelIdentifier.from(DepartureMessage.CHANNEL);
        var backend=player.player.getCurrentServer().orElseThrow();
        var client=new PluginMessageEvent(player.player,backend,channel,new byte[]{1});
        var reverse=new PluginMessageEvent(backend,player.player,channel,new byte[]{1});
        f.plugin.departureMessage(client); f.plugin.departureMessage(reverse);
        assertEquals(PluginMessageEvent.ForwardResult.handled(),client.getResult());
        assertEquals(PluginMessageEvent.ForwardResult.handled(),reverse.getResult());
    }
}
