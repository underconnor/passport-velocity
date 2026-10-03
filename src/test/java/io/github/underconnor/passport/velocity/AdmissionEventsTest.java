package io.github.underconnor.passport.velocity;

import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import io.github.underconnor.passport.core.*;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Real Velocity event classes with controlled policy/network completions; no server or account is contacted. */
class AdmissionEventsTest {
    @Test void initialFullLobbyFallsBackToLimboAndKeepsTheLobbyTicket() throws Exception {
        Fixture f=new Fixture(); f.player("lobby"); Person incoming=f.player(null);
        var choose=new PlayerChooseInitialServerEvent(incoming.player,null); await(f.plugin.initial(choose));
        assertEquals(f.servers.get("lobby"),choose.getInitialServer().orElseThrow());
        var event=f.preconnect(incoming,"lobby"); await(f.plugin.beforeConnect(event));
        assertEquals(f.servers.get("limbo"),event.getResult().getServer().orElseThrow());
        assertEquals("lobby",f.queue.position(f.key(incoming)).orElseThrow().ticket().server());
        assertEquals("limbo",f.queue.reservation(f.key(incoming)).orElseThrow().server());
        incoming.server="limbo"; f.plugin.postConnected(new ServerPostConnectEvent(incoming.player,null));
        assertTrue(f.queue.reservation(f.key(incoming)).isEmpty());
        assertTrue(f.queue.position(f.key(incoming)).isPresent());
    }
    @Test void unverifiedInitialLoginStillRespectsLimboCapacity() throws Exception {
        Fixture f=new Fixture(); f.player("limbo"); Person incoming=f.player(null);
        var event=f.preconnect(incoming,"limbo"); await(f.plugin.beforeConnect(event));
        assertFalse(event.getResult().isAllowed()); assertFalse(incoming.active);
        assertTrue(f.queue.position(f.key(incoming)).isEmpty());
    }
    @Test void anExternalPluginTransferIsQueuedAndLeavesTheCurrentBackendUntouched() throws Exception {
        Fixture f=new Fixture(); f.player("build"); Person player=f.player("lobby");
        var event=f.preconnect(player,"build"); await(f.plugin.beforeConnect(event));
        assertFalse(event.getResult().isAllowed()); assertEquals("lobby",player.server);
        assertEquals("build",f.queue.position(f.key(player)).orElseThrow().ticket().server());
    }
    @Test void aStalePlayersEventCannotReserveForANewerLoginOfTheSameUuid() throws Exception {
        Fixture f=new Fixture(); Person old=f.player(null); Person fresh=f.player(null,old.uuid);
        var event=f.preconnect(old,"lobby"); await(f.plugin.beforeConnect(event));
        assertFalse(event.getResult().isAllowed()); assertTrue(f.queue.reservation(f.key(fresh)).isEmpty());
    }
    @Test void cancellingDuringFreshPolicyLookupPreventsLateQueueEnrollmentAndAutoRetry() throws Exception {
        Fixture f=new Fixture(); f.player("lobby"); Person player=f.player("limbo");
        CompletableFuture<Policy> pending=new CompletableFuture<>(); f.source=uuid -> pending;
        var event=f.preconnect(player,"lobby"); EventTask task=f.plugin.beforeConnect(event);
        invoke(f.plugin,"leaveQueue",f.session(player)); pending.complete(f.policy(player,false)); await(task);
        assertFalse(event.getResult().isAllowed()); assertTrue(f.queue.position(f.key(player)).isEmpty());
        QueueIntent intent=(QueueIntent)field(f.session(player),"queueIntent"); assertTrue(intent.automaticPaused());
        invoke(f.plugin,"moveDefault",f.session(player)); assertEquals(0,player.connectRequests);
    }
    @Test void failedApiLookupDoesNotReserveOrBypassCapacityForAnAdmin() throws Exception {
        Fixture f=new Fixture(); Person player=f.player("limbo"); f.setPolicy(f.policy(player,true));
        f.source=uuid -> CompletableFuture.failedFuture(new IllegalStateException("offline"));
        var event=f.preconnect(player,"build"); await(f.plugin.beforeConnect(event));
        assertFalse(event.getResult().isAllowed()); assertTrue(f.queue.reservation(f.key(player)).isEmpty());
    }
    @Test void fullServerBypassRequiresTheCentralAdminAndRespectsExplicitLocalDeny() throws Exception {
        Fixture f=new Fixture(); f.player("build"); Person admin=f.player("lobby"); f.setPolicy(f.policy(admin,true));
        var allowed=f.preconnect(admin,"build"); await(f.plugin.beforeConnect(allowed)); assertTrue(allowed.getResult().isAllowed());
        Person denied=f.player("lobby"); denied.permission=Tristate.FALSE; f.setPolicy(f.policy(denied,true));
        var blocked=f.preconnect(denied,"build"); await(f.plugin.beforeConnect(blocked)); assertFalse(blocked.getResult().isAllowed());
        assertTrue(f.queue.position(f.key(denied)).isPresent());
    }
    @Test void externalConnectionWinsAndManagedInProgressResultCannotFreeItsSharedSlot() throws Exception {
        Fixture f=new Fixture(); Person player=f.player("limbo"); Policy policy=f.policy(player,false); f.setPolicy(policy);
        @SuppressWarnings("unchecked") CompletableFuture<Boolean> managed=(CompletableFuture<Boolean>)invoke(f.plugin,"admitAndConnect",
            f.session(player),f.servers.get("build"),policy,null,0L);
        AdmissionQueue.Reservation reservation=f.queue.reservation(f.key(player)).orElseThrow();
        var external=f.preconnect(player,"build"); await(f.plugin.beforeConnect(external)); assertTrue(external.getResult().isAllowed());
        var duplicate=f.preconnect(player,"build"); await(f.plugin.beforeConnect(duplicate)); assertFalse(duplicate.getResult().isAllowed());
        player.connection.complete(result(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS,f.servers.get("build")));
        assertFalse(managed.get(2,TimeUnit.SECONDS)); assertEquals(reservation,f.queue.reservation(f.key(player)).orElseThrow());
        Person other=f.player("lobby"); var blocked=f.preconnect(other,"build"); await(f.plugin.beforeConnect(blocked));
        assertFalse(blocked.getResult().isAllowed());
        player.server="build"; f.plugin.postConnected(new ServerPostConnectEvent(player.player,f.servers.get("limbo")));
        assertTrue(f.queue.reservation(f.key(player)).isEmpty());
        assertFalse(f.queue.canAttempt(f.queue.position(f.key(other)).orElseThrow().ticket(),false,System.nanoTime()));
    }
    @Test void adminDowngradeBetweenPreflightAndPreConnectQueuesBehindExistingUsers() throws Exception {
        Fixture f=new Fixture(); f.player("build"); Person waiter=f.player("lobby"),admin=f.player("lobby");
        await(f.plugin.beforeConnect(f.preconnect(waiter,"build")));
        Policy adminPolicy=f.policy(admin,true); f.setPolicy(adminPolicy);
        invoke(f.plugin,"admitAndConnect",f.session(admin),f.servers.get("build"),adminPolicy,null,0L);
        f.setPolicy(f.policy(admin,false)); var event=f.preconnect(admin,"build"); await(f.plugin.beforeConnect(event));
        assertFalse(event.getResult().isAllowed()); assertTrue(f.queue.reservation(f.key(admin)).isEmpty());
        assertEquals(2,f.queue.position(f.key(admin)).orElseThrow().position());
    }
    @Test void revocationWhileQueuedRemovesTheTicketWithoutConnecting() throws Exception {
        Fixture f=new Fixture(); f.player("build"); Person player=f.player("lobby");
        await(f.plugin.beforeConnect(f.preconnect(player,"build")));
        f.setPolicy(new Policy(player.uuid,"revoked",Set.of(),"","",100,Instant.now(),Instant.now().plusSeconds(60)));
        invoke(f.plugin,"queueTick",f.session(player),System.nanoTime());
        assertTrue(f.queue.position(f.key(player)).isEmpty()); assertEquals(0,player.connectRequests);
    }
    @Test void watchdogDisconnectsBeforeReturningAnUnresolvedSlot() throws Exception {
        Fixture f=new Fixture(); Person player=f.player("limbo");
        f.queue.request(f.key(player),"build",f.policy(player,false),false,Instant.now(),System.nanoTime()-TimeUnit.SECONDS.toNanos(46));
        invoke(f.plugin,"tick"); assertFalse(player.active);
        assertTrue(f.queue.reservation(f.key(player)).isPresent()); // physical disconnect event must remove it
    }
    @Test void aVacantSlotAutomaticallyPromotesTheHeadThroughFreshPolicyAndARealEventSequence() throws Exception {
        Fixture f=new Fixture(); Person occupant=f.player("build"),first=f.player("lobby"),second=f.player("lobby");
        await(f.plugin.beforeConnect(f.preconnect(first,"build"))); await(f.plugin.beforeConnect(f.preconnect(second,"build")));
        set(f.session(first),"playReady",true); set(f.session(second),"playReady",true);
        occupant.server=null;
        invoke(f.plugin,"queueTick",f.session(second),System.nanoTime()); assertEquals(0,second.connectRequests);
        invoke(f.plugin,"queueTick",f.session(first),System.nanoTime()); assertEquals(1,first.connectRequests);
        assertTrue(f.queue.position(f.key(first)).isEmpty());
        var gate=f.preconnect(first,"build"); await(f.plugin.beforeConnect(gate)); assertTrue(gate.getResult().isAllowed());
        first.server="build"; f.plugin.postConnected(new ServerPostConnectEvent(first.player,f.servers.get("lobby")));
        first.connection.complete(result(ConnectionRequestBuilder.Status.SUCCESS,f.servers.get("build")));
        assertTrue(f.queue.reservation(f.key(first)).isEmpty());
        assertEquals(1,f.queue.position(f.key(second)).orElseThrow().position());
        invoke(f.plugin,"queueTick",f.session(second),System.nanoTime()); assertEquals(0,second.connectRequests);
    }

    static final class Person {
        UUID uuid; Player player; boolean active=true; String server; Tristate permission=Tristate.UNDEFINED;
        int connectRequests; CompletableFuture<ConnectionRequestBuilder.Result> connection=new CompletableFuture<>();
        List<Component> messages=new ArrayList<>();
    }
    final class Fixture {
        final Map<String,RegisteredServer> servers=new HashMap<>();
        final List<Person> people=new ArrayList<>();
        final Map<UUID,Policy> responses=new HashMap<>();
        final PassportVelocity plugin;
        final AdmissionQueue queue;
        final PolicyCache cache;
        Function<UUID,CompletableFuture<Policy>> source=uuid -> CompletableFuture.completedFuture(responses.get(uuid));
        long policyVersion;
        Fixture() throws Exception {
            for(String name:List.of("lobby","build","limbo")) {
                ServerInfo info=new ServerInfo(name,new InetSocketAddress("127.0.0.1",25565));
                servers.put(name,stub(RegisteredServer.class,(method,args) -> switch(method.getName()) {
                    case "getServerInfo" -> info;
                    case "getPlayersConnected" -> people.stream().filter(p -> name.equals(p.server)).map(p -> p.player).toList();
                    default -> null;
                }));
            }
            ProxyServer proxy=stub(ProxyServer.class,(method,args) -> switch(method.getName()) {
                case "getServer" -> Optional.ofNullable(servers.get(args[0]));
                case "getAllServers" -> servers.values();
                default -> null;
            });
            plugin=new PassportVelocity(proxy,stub(Logger.class,(method,args) -> null));
            set(plugin,"ready",true); set(plugin,"waiting","limbo"); set(plugin,"defaultServer","lobby");
            queue=new AdmissionQueue(Map.of("lobby",1,"build",1,"limbo",1),server -> {
                Set<AdmissionQueue.Key> keys=new HashSet<>();
                for(Person person:people) if(server.equals(person.server)) try { keys.add(key(person)); } catch(Exception error) { throw new RuntimeException(error); }
                return keys;
            });
            set(plugin,"admissions",queue); cache=(PolicyCache)field(plugin,"policies");
            set(plugin,"refreshes",new PolicyRefreshes(uuid -> source.apply(uuid).thenApply(policy -> {
                if(!cache.acceptOrCurrent(policy)) throw new IllegalStateException("stale policy"); return policy;
            })));
        }
        Person player(String server) throws Exception { return player(server,UUID.randomUUID()); }
        Person player(String server,UUID uuid) throws Exception {
            Person person=new Person(); person.uuid=uuid; person.server=server;
            person.player=stub(Player.class,(method,args) -> switch(method.getName()) {
                case "getUniqueId" -> person.uuid;
                case "getUsername" -> "TestPlayer";
                case "isActive" -> person.active;
                case "isOnlineMode" -> true;
                case "getPermissionValue" -> person.permission;
                case "disconnect" -> { person.active=false; yield null; }
                case "sendMessage","sendActionBar" -> { if(args[args.length-1] instanceof Component text) person.messages.add(text); yield null; }
                case "getCurrentServer" -> person.server==null ? Optional.empty() : Optional.of(stub(ServerConnection.class,(connection,parameters) -> switch(connection.getName()) {
                    case "getPlayer" -> person.player;
                    case "getServer" -> servers.get(person.server);
                    case "getServerInfo" -> servers.get(person.server).getServerInfo();
                    default -> null;
                }));
                case "createConnectionRequest" -> stub(ConnectionRequestBuilder.class,(request,parameters) -> {
                    if(request.getName().equals("getServer")) return args[0];
                    if(request.getName().equals("connect")) { person.connectRequests++; return person.connection; }
                    return null;
                });
                default -> null;
            });
            people.add(person); plugin.login(new PostLoginEvent(person.player)); setPolicy(policy(person,false));
            return person;
        }
        Policy policy(Person player,boolean admin) {
            Instant now=Instant.now(); return new Policy(player.uuid,"active",Set.of("lobby","build"),"","",++policyVersion,now,now.plusSeconds(60),false,null,admin,Map.of("lobby","로비","build","건축"));
        }
        void setPolicy(Policy policy) { responses.put(policy.minecraftUuid(),policy); cache.acceptOrCurrent(policy); }
        Object session(Person player) throws Exception { return ((Map<?,?>)field(plugin,"sessions")).get(player.uuid); }
        AdmissionQueue.Key key(Person player) throws Exception { return (AdmissionQueue.Key)field(session(player),"queueKey"); }
        ServerPreConnectEvent preconnect(Person player,String target) { return new ServerPreConnectEvent(player.player,servers.get(target),servers.get(player.server)); }
    }
    static ConnectionRequestBuilder.Result result(ConnectionRequestBuilder.Status status,RegisteredServer server) {
        return new ConnectionRequestBuilder.Result() {
            public ConnectionRequestBuilder.Status getStatus() { return status; }
            public Optional<Component> getReasonComponent() { return Optional.empty(); }
            public RegisteredServer getAttemptedConnection() { return server; }
        };
    }
    interface Stub { Object call(Method method,Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") static <T> T stub(Class<T> type,Stub handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args) -> {
            if(method.getDeclaringClass()==Object.class) return switch(method.getName()) {
                case "equals" -> object==args[0]; case "hashCode" -> System.identityHashCode(object); default -> type.getSimpleName();
            };
            Object value=handler.call(method,args==null ? new Object[0] : args);
            if(value!=null || !method.getReturnType().isPrimitive() || method.getReturnType()==void.class) return value;
            if(method.getReturnType()==boolean.class) return false;
            if(method.getReturnType()==long.class) return 0L;
            return 0;
        });
    }
    static Object field(Object object,String name) throws Exception { Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    static void set(Object object,String name,Object value) throws Exception { Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value); }
    static Object invoke(Object object,String name,Object... args) throws Exception {
        Method method=Arrays.stream(object.getClass().getDeclaredMethods()).filter(m -> m.getName().equals(name) && m.getParameterCount()==args.length).findFirst().orElseThrow();
        method.setAccessible(true); return method.invoke(object,args);
    }
    static void await(EventTask task) throws Exception {
        if(task==null) return;
        CompletableFuture<Void> done=new CompletableFuture<>();
        task.execute(new Continuation() { public void resume() { done.complete(null); } public void resumeWithException(Throwable error) { done.completeExceptionally(error); } });
        done.get(2,TimeUnit.SECONDS);
    }
}
