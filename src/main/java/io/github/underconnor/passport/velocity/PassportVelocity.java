package io.github.underconnor.passport.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.command.*;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.github.underconnor.passport.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Plugin(id="passport", name="Passport", version="0.1.0-SNAPSHOT", authors={"underconnor"})
public final class PassportVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final PolicyCache policies = new PolicyCache();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private ApiClient api;
    private String teleportSecret;
    private final TeleportRequests teleports=new TeleportRequests();
    private static final MinecraftChannelIdentifier DEPARTURE_CHANNEL=MinecraftChannelIdentifier.from(DepartureMessage.CHANNEL);
    private static final MinecraftChannelIdentifier TELEPORT_CHANNEL=MinecraftChannelIdentifier.from(TeleportMessage.CHANNEL);
    private PolicyRefreshes refreshes;
    private PolicyEventPoller eventPoller;
    private ServerHeartbeat heartbeat;
    private AdmissionQueue admissions;
    private volatile boolean ready;
    private String waiting, defaultServer;
    private URI webOrigin, adminOrigin;
    private static final Component DENIED = Component.text("Passport 인증 또는 서버 권한을 확인할 수 없습니다.", NamedTextColor.RED);
    private static final Component WAITING_FULL = Component.text("현재 인증 대기실이 가득 찼습니다. 잠시 후 다시 접속해주세요.",NamedTextColor.YELLOW);
    private static final class Session {
        final Player player;
        final String gameSession = UUID.randomUUID().toString();
        final AtomicBoolean refreshing = new AtomicBoolean(), linking = new AtomicBoolean(), moving = new AtomicBoolean();
        final AtomicBoolean queueChecking = new AtomicBoolean(), connecting = new AtomicBoolean();
        final AtomicLong lastCommand = new AtomicLong(), linkGeneration = new AtomicLong();
        final QueueIntent queueIntent = new QueueIntent();
        final RoutingAttempts routing = new RoutingAttempts();
        final DiscordInvitation discordInvitation;
        final AdmissionQueue.Key queueKey;
        volatile DepartureMessage departure;
        volatile LinkCompletionPoller completion;
        volatile String linkId;
        volatile Instant linkExpiry;
        volatile long nextRefresh;
        volatile boolean playReady;
        volatile long nextQueueCheck, nextQueueBar;
        Session(Player player) {
            this.player = player; this.discordInvitation = new DiscordInvitation(player.getUniqueId());
            this.queueKey = new AdmissionQueue.Key(player.getUniqueId(),gameSession);
        }
    }
    @Inject public PassportVelocity(ProxyServer proxy, Logger logger) { this.proxy = proxy; this.logger = logger; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("passport").plugin(this).build(), new PassportCommand());
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("서버").plugin(this).build(), new ServerShortcut());
        var builtin=proxy.getCommandManager().getCommandMeta("server");
        if(builtin!=null) proxy.getCommandManager().unregister(builtin);
        proxy.getCommandManager().unregister("velocity:server");
        try {
            waiting = ApiClient.env("PASSPORT_WAITING_SERVER", "passport-limbo");
            defaultServer = ApiClient.env("PASSPORT_DEFAULT_SERVER", "lobby");
            if (!proxy.getConfiguration().isOnlineMode()) throw new IllegalArgumentException("Velocity online-mode must be true");
            if (proxy.getServer(waiting).isEmpty() || waiting.equals(defaultServer)) throw new IllegalArgumentException("A separate waiting server must exist");
            admissions=new AdmissionQueue(ServerCapacities.parse(System.getenv("PASSPORT_SERVER_CAPACITIES"),
                proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).collect(java.util.stream.Collectors.toSet())),this::occupants);
            webOrigin = URI.create(ApiClient.env("PASSPORT_WEB_ORIGIN", "https://passport.example"));
            if (webOrigin.getHost() == null || webOrigin.getUserInfo() != null || !("https".equals(webOrigin.getScheme()) || ("http".equals(webOrigin.getScheme()) && Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP", "false"))))) throw new IllegalArgumentException("Invalid web origin");
            adminOrigin=URI.create(ApiClient.env("PASSPORT_ADMIN_ORIGIN","https://admin-overworld.flyjung.kr"));
            if(!"https".equals(adminOrigin.getScheme()) || adminOrigin.getHost()==null || adminOrigin.getUserInfo()!=null || adminOrigin.getQuery()!=null || adminOrigin.getFragment()!=null) throw new IllegalArgumentException("Invalid admin origin");
            api = new ApiClient(ApiClient.env("PASSPORT_API_BASE_URL", "https://api.passport.example/"),
                System.getenv("API_SERVICE_TOKEN"), Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP", "false")));
            teleportSecret=TeleportSecrets.resolve(System.getenv("PASSPORT_TELEPORT_SECRET"),System.getenv("API_SERVICE_TOKEN"));
            proxy.getChannelRegistrar().register(TELEPORT_CHANNEL,DEPARTURE_CHANNEL);
            refreshes = new PolicyRefreshes(uuid -> {
                Session requestingSession = sessions.get(uuid);
                return api.policy(uuid).thenApply(policy -> {
                    if (!policies.acceptOrCurrent(policy)) throw new CompletionException(new IllegalStateException("Stale policy response"));
                    if (requestingSession != null && current(requestingSession)) requestingSession.discordInvitation.observe(policy,Instant.now());
                    return policy;
                });
            });
            eventPoller = new PolicyEventPoller(api::events,
                () -> sessions.values().stream().filter(this::current).map(s -> s.player.getUniqueId()).collect(java.util.stream.Collectors.toSet()),
                this::refreshFromEvent);
            heartbeat = new ServerHeartbeat(() -> api.heartbeat("velocity", ServerRegistration.backends(
                proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).toList(), waiting)), available -> {
                if (ready) { if (available) logger.info("Passport server registration recovered");
                    else logger.warn("Passport server registration unavailable; existing access checks remain active"); }
            });
            ready = true;
            heartbeat.poll().exceptionally(error -> null);
            proxy.getScheduler().buildTask(this, () -> {
                if (ready) heartbeat.poll().exceptionally(error -> null);
            }).delay(Duration.ofSeconds(30)).repeat(Duration.ofSeconds(30)).schedule();
            proxy.getScheduler().buildTask(this, this::tick).repeat(Duration.ofSeconds(1)).schedule();
            proxy.getScheduler().buildTask(this, () -> {
                if (ready) eventPoller.poll().exceptionally(error -> null);
            }).repeat(Duration.ofSeconds(2)).schedule();
            logger.info("Passport enabled: waiting server {}, access checks fail closed", waiting);
        } catch (RuntimeException error) { logger.error("Passport configuration invalid; player admission is closed: {}", error.getMessage()); }
    }
    @Subscribe public void login(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (!ready || !player.isOnlineMode()) { player.disconnect(DENIED); return; }
        Session session = new Session(player);
        Session previous=sessions.put(player.getUniqueId(), session);
        if(previous!=null) { admissions.disconnected(previous.queueKey); cancel(previous); }
    }
    @Subscribe(order=PostOrder.LAST) public EventTask initial(PlayerChooseInitialServerEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (!ready || session == null || session.player!=event.getPlayer() || !current(session)) { event.getPlayer().disconnect(DENIED); return null; }
        RegisteredServer fallback = proxy.getServer(waiting).orElse(null);
        if (fallback == null) { event.getPlayer().disconnect(DENIED); return null; }
        event.setInitialServer(fallback);
        return EventTask.resumeWhenComplete(refreshes.fresh(session.player.getUniqueId()).handle((policy,error) -> {
            if (current(session) && error == null && policies.allows(session.player.getUniqueId(),defaultServer,Instant.now()))
                proxy.getServer(defaultServer).ifPresent(event::setInitialServer);
            return null;
        }));
    }
    @Subscribe(order=PostOrder.LAST) public EventTask beforeConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) return null;
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (!ready || session == null || session.player!=event.getPlayer() || !current(session)) { event.setResult(ServerPreConnectEvent.ServerResult.denied()); return null; }
        String serverId = target.getServerInfo().getName();
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        if (serverId.equals(waiting)) { allowWaiting(event,session); return null; }
        long generation=session.queueIntent.generation();
        return EventTask.resumeWhenComplete(refreshes.fresh(session.player.getUniqueId()).handle((policy, error) -> {
            synchronized(session.queueIntent) {
                if(!current(session) || generation!=session.queueIntent.generation()) return null;
                AdmissionQueue.Decision decision=error==null ? admissions.request(session.queueKey,serverId,
                    policies.get(session.player.getUniqueId()).orElse(null),adminDenied(session),Instant.now(),System.nanoTime()) : null;
                if(decision!=null && decision.status()==AdmissionQueue.Status.ALLOWED && admissions.begin(decision.reservation())) event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
                else {
                    if(decision==null || decision.status()==AdmissionQueue.Status.DENIED) {
                        admissions.reservation(session.queueKey).filter(r -> r.server().equals(serverId)).ifPresent(admissions::releaseUnstarted);
                        cancelQueueFor(session,serverId);
                    }
                    if(decision!=null && decision.status()==AdmissionQueue.Status.QUEUED && decision.joined() && session.playReady) queueStatus(session,true);
                    if(session.player.getCurrentServer().isEmpty() && admissions.reservation(session.queueKey).isEmpty()) allowWaiting(event,session);
                    else if((decision==null || decision.status()==AdmissionQueue.Status.DENIED) && !(serverId.equals(defaultServer) && session.routing.inProgress())) session.player.sendMessage(DENIED);
                }
                return null;
            }
        }));
    }
    private void allowWaiting(ServerPreConnectEvent event,Session session) {
        if(!current(session)) return;
        AdmissionQueue.Decision decision=admissions.waiting(session.queueKey,waiting,System.nanoTime());
        if(decision.status()==AdmissionQueue.Status.ALLOWED && admissions.begin(decision.reservation())) proxy.getServer(waiting).ifPresent(server -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(server)));
        else if(decision.status()==AdmissionQueue.Status.BUSY) return;
        else session.player.disconnect(WAITING_FULL);
    }
    @Subscribe public void connected(ServerConnectedEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if(session==null || session.player!=event.getPlayer() || !current(session)) return;
        DepartureMessage previous=session.departure;
        String destination=event.getServer().getServerInfo().getName();
        session.departure=DepartureMessage.begin(event.getPlayer().getUniqueId(),destination,Instant.now());
        if(previous!=null && !previous.serverId().equals(destination)) event.getPreviousServer()
            .filter(server -> server.getServerInfo().getName().equals(previous.serverId()))
            .ifPresent(server -> server.sendPluginMessage(DEPARTURE_CHANNEL,previous.transferred(Instant.now()).encode(teleportSecret)));
        if (session != null && event.getServer().getServerInfo().getName().equals(defaultServer)) session.routing.succeeded();
        if (session != null && event.getServer().getServerInfo().getName().equals(waiting)) {
            refresh(session).whenComplete((valid,error) -> {
                if (!current(session)) return;
                if (error == null && Boolean.TRUE.equals(valid) && policies.allows(session.player.getUniqueId(), defaultServer, Instant.now())) moveDefault(session);
                else if (error == null && Boolean.TRUE.equals(valid)) {
                    Policy policy = policies.get(session.player.getUniqueId()).orElse(null);
                    if (policy != null && (policy.status().equals("unlinked") || policy.status().equals("pending"))) createLink(session);
                    else session.player.sendMessage(Component.text("현재 입장 가능한 서버가 없습니다. /passport status", NamedTextColor.YELLOW));
                } else session.player.sendMessage(Component.text("인증 서비스를 확인할 수 없습니다. 잠시 후 /passport 를 입력하세요.", NamedTextColor.YELLOW));
            });
        }
    }
    @Subscribe public void postConnected(ServerPostConnectEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer() && current(session)) {
            DepartureMessage visit=session.departure;
            if(visit!=null && visit.valid(Instant.now())) session.player.getCurrentServer()
                .filter(server -> server.getServerInfo().getName().equals(visit.serverId()))
                .ifPresent(server -> server.sendPluginMessage(DEPARTURE_CHANNEL,visit.encode(teleportSecret)));
            session.playReady = true;
            session.player.getCurrentServer().ifPresent(server -> admissions.arrived(session.queueKey,server.getServerInfo().getName()));
            if(event.getPreviousServer()==null) queueStatus(session,false);
            inviteToDiscord(session);
        }
    }
    private void inviteToDiscord(Session session) {
        if (session.discordInvitation.claim(policies.get(session.player.getUniqueId()).orElse(null),
                current(session),session.playReady && session.player.getCurrentServer().isPresent(),Instant.now()) && current(session)) {
            session.player.sendMessage(Component.text("디스코드 서버에 가입하시면 더 다양한 정보를 빠르게 얻으실 수 있습니다.",NamedTextColor.GRAY)
                .append(Component.newline()).append(Component.text("[디스코드 서버 가입하기]",NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl("https://discord.gg/V3ABprEEmw"))));
        }
    }
    @Subscribe(order=PostOrder.LAST) public void kicked(KickedFromServerEvent event) {
        Session kickedSession=sessions.get(event.getPlayer().getUniqueId());
        if(kickedSession==null || kickedSession.player!=event.getPlayer()) { event.setResult(KickedFromServerEvent.DisconnectPlayer.create(DENIED)); return; }
        if(admissions!=null) admissions.failed(kickedSession.queueKey,event.getServer().getServerInfo().getName(),System.nanoTime());
        if (!ready || event.getServer().getServerInfo().getName().equals(waiting)) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(DENIED));
        } else {
            Session session = sessions.get(event.getPlayer().getUniqueId());
            if (session != null && event.getServer().getServerInfo().getName().equals(defaultServer)
                && session.routing.failed(System.nanoTime())) routingFailure(session);
            proxy.getServer(waiting).ifPresentOrElse(server -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(server, DENIED)),
                () -> event.setResult(KickedFromServerEvent.DisconnectPlayer.create(DENIED)));
        }
    }
    @Subscribe public void disconnect(DisconnectEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer() && sessions.remove(event.getPlayer().getUniqueId(), session)) {
            admissions.disconnected(session.queueKey); cancel(session);
        }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { ready = false; teleports.close(); proxy.getChannelRegistrar().unregister(TELEPORT_CHANNEL,DEPARTURE_CHANNEL); if (api != null) api.close(); }
    private boolean current(Session session) { return ready && session.player.isActive() && sessions.get(session.player.getUniqueId()) == session; }
    private CompletableFuture<Boolean> refresh(Session session) {
        if (!current(session)) return CompletableFuture.completedFuture(false);
        return refreshes.fetch(session.player.getUniqueId()).thenApply(policy -> current(session));
    }
    private CompletableFuture<Policy> refreshFromEvent(UUID uuid, boolean reset) {
        Session session = sessions.get(uuid);
        if (session == null || !current(session)) return CompletableFuture.completedFuture(null);
        return (reset ? refreshes.fresh(uuid) : refreshes.fetch(uuid)).thenApply(policy -> {
            if (current(session)) { enforce(session); moveDefault(session); }
            return policy;
        });
    }
    private void tick() {
        long now = System.nanoTime();
        for(AdmissionQueue.Reservation reservation:admissions.overdue(now)) {
            Session session=sessions.get(reservation.key().uuid());
            if(session!=null && session.queueKey.equals(reservation.key())) {
                session.player.getCurrentServer().ifPresent(server -> admissions.arrived(session.queueKey,server.getServerInfo().getName()));
                if(admissions.reservation(session.queueKey).filter(reservation::equals).isPresent())
                    session.player.disconnect(Component.text("서버 연결 시간이 초과되었습니다. 다시 접속해주세요.",NamedTextColor.YELLOW));
            }
            else admissions.disconnected(reservation.key());
        }
        for (Session session : sessions.values()) {
            if (!current(session)) continue;
            session.player.getCurrentServer().ifPresent(server -> admissions.arrived(session.queueKey,server.getServerInfo().getName()));
            inviteToDiscord(session);
            enforce(session);
            queueTick(session,now);
            LinkCompletionPoller completion = session.completion;
            if (completion != null) completion.tick();
            moveDefault(session);
            if (now >= session.nextRefresh && session.refreshing.compareAndSet(false, true)) {
                session.nextRefresh = now + TimeUnit.SECONDS.toNanos(20 + ThreadLocalRandom.current().nextInt(6));
                refresh(session).whenComplete((valid,error) -> {
                    session.refreshing.set(false);
                    if (current(session)) {
                        enforce(session);
                        if (error == null && Boolean.TRUE.equals(valid) && session.player.getCurrentServer().map(c -> c.getServerInfo().getName().equals(waiting)).orElse(false)) moveDefault(session);
                    }
                });
            }
        }
    }
    private void enforce(Session session) {
        session.player.getCurrentServer().ifPresent(connection -> {
            String currentServer = connection.getServerInfo().getName();
            if (!currentServer.equals(waiting) && !policies.allows(session.player.getUniqueId(), currentServer, Instant.now())) quarantine(session);
        });
    }
    private void quarantine(Session session) {
        if (!session.moving.compareAndSet(false,true)) return;
        proxy.getServer(waiting).ifPresentOrElse(server -> session.player.createConnectionRequest(server).connect()
            .orTimeout(3,TimeUnit.SECONDS).whenComplete((result,error) -> {
                session.moving.set(false);
                if (current(session) && (error != null || !result.isSuccessful())) session.player.disconnect(DENIED);
            }), () -> { session.moving.set(false); session.player.disconnect(DENIED); });
    }
    private void moveDefault(Session session) {
        if (!current(session) || session.queueIntent.automaticPaused() || admissions.position(session.queueKey).isPresent()
                || admissions.reservation(session.queueKey).isPresent() || session.connecting.get()) return;
        String currentServer = session.player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        RegisteredServer target = policies.get(session.player.getUniqueId())
            .flatMap(policy -> AutomaticRouting.target(policy, currentServer, waiting, defaultServer, Instant.now()))
            .flatMap(proxy::getServer).orElse(null);
        if (target == null) return;
        long attempt = session.routing.begin(System.nanoTime());
        if (attempt == 0) return;
        long generation=session.queueIntent.generation();
        refreshes.fresh(session.player.getUniqueId()).thenCompose(policy -> {
            if(!current(session) || session.queueIntent.automaticPaused() || generation!=session.queueIntent.generation()) return CompletableFuture.completedFuture(false);
            return admitAndConnect(session,target,policy,null,generation);
        }).whenComplete((success,error) -> {
            boolean waitingInQueue=admissions.position(session.queueKey).isPresent();
            if(session.routing.complete(attempt,waitingInQueue || error==null && Boolean.TRUE.equals(success),System.nanoTime()) && current(session)) routingFailure(session);
        });
    }
    private Set<AdmissionQueue.Key> occupants(String server) {
        return proxy.getServer(server).map(value -> value.getPlayersConnected().stream().map(player -> {
            Session session=sessions.get(player.getUniqueId());
            return session!=null && session.player==player ? session.queueKey
                : new AdmissionQueue.Key(player.getUniqueId(),"external-"+System.identityHashCode(player));
        })
            .collect(java.util.stream.Collectors.toSet())).orElse(Set.of());
    }
    private boolean adminDenied(Session session) { return session.player.getPermissionValue("passport.admin")==Tristate.FALSE; }
    private void cancelQueueFor(Session session,String server) {
        admissions.position(session.queueKey).map(AdmissionQueue.Position::ticket).filter(ticket -> ticket.server().equals(server)).ifPresent(admissions::cancel);
    }
    private CompletableFuture<Boolean> admitAndConnect(Session session,RegisteredServer target,Policy policy,AdmissionQueue.Ticket ticket,long generation) {
        synchronized(session.queueIntent) {
            if(!current(session) || !session.queueIntent.current(generation)) return CompletableFuture.completedFuture(false);
            String server=target.getServerInfo().getName();
            Policy latest=policies.get(session.player.getUniqueId()).orElse(policy);
            AdmissionQueue.Decision decision=ticket==null
                ? admissions.request(session.queueKey,server,latest,adminDenied(session),Instant.now(),System.nanoTime())
                : admissions.promote(ticket,latest,adminDenied(session),Instant.now(),System.nanoTime());
            if(decision.status()==AdmissionQueue.Status.QUEUED) {
                if(decision.joined()) queueStatus(session,false);
                return CompletableFuture.completedFuture(false);
            }
            if(decision.status()!=AdmissionQueue.Status.ALLOWED) {
                if(decision.status()==AdmissionQueue.Status.DENIED) {
                    cancelQueueFor(session,server); session.player.sendMessage(DENIED);
                }
                return CompletableFuture.completedFuture(false);
            }
            if(decision.reservation()==null) return CompletableFuture.completedFuture(true);
            // An existing reservation belongs to a connection already in progress, possibly from another plugin.
            if(!decision.joined()) return CompletableFuture.completedFuture(false);
            AdmissionQueue.Reservation reservation=decision.reservation();
            if(!session.connecting.compareAndSet(false,true)) { admissions.release(reservation,false,System.nanoTime()); return CompletableFuture.completedFuture(false); }
            session.player.sendActionBar(Component.empty());
            CompletableFuture<ConnectionRequestBuilder.Result> connection;
            try { connection=session.player.createConnectionRequest(target).connect(); }
            catch(RuntimeException error) {
                session.connecting.set(false); admissions.release(reservation,true,System.nanoTime()); return CompletableFuture.failedFuture(error);
            }
            // Never release capacity merely because a wrapper timed out: the underlying connection could still succeed.
            return connection.handle((result,error) -> {
                session.connecting.set(false);
                boolean success=error==null && result.isSuccessful()
                    && session.player.getCurrentServer().map(current -> current.getServerInfo().getName().equals(server)).orElse(false);
                if(success) admissions.arrived(session.queueKey,server);
                else {
                    if(error!=null || result.getStatus()==ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) admissions.release(reservation,true,System.nanoTime());
                    else admissions.releaseUnstarted(reservation);
                    if(current(session) && admissions.position(session.queueKey).isEmpty()) {
                        if(ticket!=null) session.queueIntent.failed();
                        session.player.sendMessage(Component.text("서버에 연결하지 못했습니다. 잠시 후 다시 시도해주세요.",NamedTextColor.YELLOW));
                    }
                }
                return success;
            });
        }
    }
    private void queueStatus(Session session,boolean showEmpty) {
        if(!current(session) || !session.playReady) return;
        Optional<AdmissionQueue.Position> status=admissions.position(session.queueKey);
        if(status.isEmpty()) {
            if(showEmpty) session.player.sendMessage(Component.text("현재 대기 중인 서버가 없습니다.",NamedTextColor.GRAY));
            return;
        }
        Policy policy=policies.get(session.player.getUniqueId()).orElse(null);
        AdmissionQueue.Position position=status.get();
        if(policy==null || !policy.allows(position.ticket().server(),Instant.now())) return;
        session.player.sendMessage(Component.text(policy.label(position.ticket().server())+" 입장 대기 · "+position.position()+"번째 / "+position.total()+"명",NamedTextColor.YELLOW)
            .append(Component.text("  [대기 취소]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/passport queue leave"))));
    }
    private void leaveQueue(Session session) {
        boolean removed;
        synchronized(session.queueIntent) {
            session.queueIntent.cancel();
            removed=admissions.cancel(session.queueKey);
        }
        session.player.sendActionBar(Component.empty());
        session.player.sendMessage(Component.text(removed ? "대기열에서 나왔습니다. /서버 명령어로 다시 선택할 수 있습니다." : "현재 대기 중인 서버가 없습니다.",NamedTextColor.GRAY));
    }
    private void queueTick(Session session,long clock) {
        AdmissionQueue.Position position=admissions.position(session.queueKey).orElse(null);
        if(position==null) return;
        AdmissionQueue.Ticket ticket=position.ticket();
        Policy policy=policies.get(session.player.getUniqueId()).orElse(null);
        if(policy==null || !policy.allows(ticket.server(),Instant.now())) {
            if(admissions.cancel(ticket)) {
                session.player.sendActionBar(Component.empty());
                session.player.sendMessage(Component.text("접속 권한을 확인할 수 없어 대기열에서 나왔습니다.",NamedTextColor.YELLOW));
            }
            return;
        }
        if(session.playReady && clock>=session.nextQueueBar) {
            session.nextQueueBar=clock+TimeUnit.SECONDS.toNanos(3);
            session.player.sendActionBar(Component.text(policy.label(ticket.server())+" 입장 대기 · "+position.position()+" / "+position.total()+" · /passport queue leave",NamedTextColor.YELLOW));
        }
        boolean bypass=AdmissionQueue.bypass(policy,session.player.getUniqueId(),ticket.server(),adminDenied(session),Instant.now());
        if(clock<session.nextQueueCheck || !session.playReady || !admissions.canAttempt(ticket,bypass,clock)
                || !session.queueChecking.compareAndSet(false,true)) return;
        session.nextQueueCheck=clock+TimeUnit.SECONDS.toNanos(5);
        long generation=session.queueIntent.generation();
        refreshes.fresh(session.player.getUniqueId()).thenCompose(fresh -> {
            if(!current(session) || generation!=session.queueIntent.generation()) return CompletableFuture.completedFuture(false);
            RegisteredServer target=proxy.getServer(ticket.server()).orElse(null);
            if(target==null) { admissions.cancel(ticket); return CompletableFuture.completedFuture(false); }
            return admitAndConnect(session,target,fresh,ticket,generation);
        }).whenComplete((success,error) -> session.queueChecking.set(false));
    }
    private void routingFailure(Session session) {
        if (current(session)) session.player.sendMessage(Component.text("로비 연결을 재시도합니다. /passport status",NamedTextColor.YELLOW));
    }
    private boolean currentLink(Session session, String id, long generation) {
        return current(session) && session.linkGeneration.get() == generation && Objects.equals(session.linkId,id);
    }
    private void linked(Session session, String id, long generation) {
        if (!currentLink(session,id,generation)) return;
        refreshes.fresh(session.player.getUniqueId()).whenComplete((policy,error) -> {
            if (!currentLink(session,id,generation)) return;
            if (error != null) session.player.sendMessage(Component.text("계정 연결은 완료됐지만 서버 권한 확인이 지연되고 있습니다. 접속을 유지하면 자동으로 다시 확인합니다.",NamedTextColor.YELLOW));
            else if (!policies.allows(session.player.getUniqueId(),defaultServer,Instant.now()))
                session.player.sendMessage(Component.text("계정 연결은 완료됐지만 현재 로비 입장 권한이 없습니다. 웹의 회원 상태를 확인하세요.",NamedTextColor.YELLOW));
            else {
                session.player.sendMessage(Component.text("Passport 시스템 등록 완료",NamedTextColor.GREEN));
                moveDefault(session);
            }
        });
    }
    private void createLink(Session session) {
        if (!current(session) || !session.linking.compareAndSet(false,true)) return;
        // Reissuing cancels the previous pending link before making a new one.
        CompletableFuture<Void> previousCancellation = cancel(session);
        long generation = session.linkGeneration.get();
        previousCancellation.handle((ignored,error) -> null).thenCompose(ignored -> api.createLink(session.player.getUniqueId(),session.player.getUsername(),session.gameSession))
            .whenComplete((link,error) -> {
                session.linking.set(false);
                if (!current(session) || session.linkGeneration.get() != generation) {
                    if (error == null) api.cancel(link.get("id").getAsString(),session.player.getUniqueId(),session.gameSession).exceptionally(e -> null);
                    return;
                }
                if (error != null) { linkFeedback(session, LinkFeedback.creation(error)); return; }
                try {
                    String id = UUID.fromString(link.get("id").getAsString()).toString();
                    URI url = URI.create(link.get("url").getAsString());
                    Instant expiry = Instant.parse(link.get("expiresAt").getAsString());
                    if (!Objects.equals(url.getScheme(),webOrigin.getScheme()) || !Objects.equals(url.getHost(),webOrigin.getHost())
                        || url.getPort()!=webOrigin.getPort() || url.getUserInfo()!=null || !expiry.isAfter(Instant.now())
                        || expiry.isAfter(Instant.now().plusSeconds(305))) throw new IllegalArgumentException("link");
                    session.linkId=id; session.linkExpiry=expiry;
                    session.completion = new LinkCompletionPoller(id,expiry,() -> currentLink(session,id,generation),Instant::now,
                        () -> api.gameInspect(id,session.player.getUniqueId(),session.gameSession),
                        () -> api.confirm(id,session.player.getUniqueId(),session.gameSession)
                            .thenApply(value -> LinkInspection.confirmationStatus(value,id,expiry,Instant.now())),
                        () -> linked(session,id,generation), feedback -> { if (currentLink(session,id,generation)) linkFeedback(session,feedback); });
                    session.player.sendMessage(Component.text("숭실대학교 AI소프트웨어학부 소모임 오버월드 인증 시스템 passport",NamedTextColor.WHITE));
                    session.player.sendMessage(Component.text("서버 연결을 위해 u-saint 연동이 필요합니다. 아래 버튼을 눌러 연동을 진행해주세요.",NamedTextColor.WHITE));
                    session.player.sendMessage(Component.text("[u-saint 연동하기]", NamedTextColor.GREEN).clickEvent(ClickEvent.openUrl(url.toString())));
                } catch (RuntimeException e) { session.player.sendMessage(DENIED); }
            });
    }
    private CompletableFuture<Void> cancel(Session session) {
        session.linkGeneration.incrementAndGet();
        LinkCompletionPoller completion = session.completion; session.completion=null;
        if (completion != null) completion.stop();
        String id=session.linkId; session.linkId=null; session.linkExpiry=null;
        return id==null || api==null ? CompletableFuture.completedFuture(null) : api.cancel(id,session.player.getUniqueId(),session.gameSession);
    }
    private void linkFeedback(Session session, LinkFeedback feedback) {
        if (!current(session)) return;
        session.player.sendMessage(Component.text(feedback.message(), NamedTextColor.YELLOW));
        if (feedback.refreshPolicy())
            refresh(session).thenAccept(valid -> { if (valid && current(session)) moveDefault(session); }).exceptionally(error -> null);
    }
    @Subscribe(order=PostOrder.LAST) public void blockBuiltin(CommandExecuteEvent event) {
        if(!(event.getCommandSource() instanceof Player player)) return;
        if(CommandSelection.blockedBuiltin(event.getCommand())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            event.getCommandSource().sendMessage(Component.text("/서버 <서버명>",NamedTextColor.YELLOW));
        } else if(!canInspect(player) && CommandPresentation.informationCommand(event.getCommand())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            player.sendMessage(Component.text("사용 가능한 명령어는 /help 또는 /passport help에서 확인하세요.",NamedTextColor.GRAY));
        }
    }
    private boolean canInspect(CommandSource source) { return source.getPermissionValue(CommandPresentation.INSPECT_PERMISSION)==Tristate.TRUE; }
    @Subscribe(order=PostOrder.LAST) public EventTask availableCommands(PlayerAvailableCommandsEvent event) {
        var manager=proxy.getCommandManager();
        var roots=event.getRootNode().getChildren().stream().map(node -> node.getName()).toList();
        return EventTask.resumeWhenComplete(LuckPermsCommandVisibility.hiddenRoots(roots,event.getPlayer(),manager,proxy.getPluginManager())
            .thenAccept(hidden -> CommandTreeFilter.filter(event.getRootNode(),event.getPlayer(),canInspect(event.getPlayer()),
                manager::hasCommand,name -> !hidden.contains(name) && manager.hasCommand(name,event.getPlayer()))));
    }
    private List<String> availablePassportCommands(CommandSource source) {
        if(!(source instanceof Player player)) return CommandPresentation.passportCommands(null,null,false,false,true,Instant.now());
        Session session=sessions.get(player.getUniqueId());
        return CommandPresentation.passportCommands(policies.get(player.getUniqueId()).orElse(null),player.getUniqueId(),
            session!=null && session.player==player && current(session),source.getPermissionValue("passport.admin")==Tristate.FALSE,false,Instant.now());
    }
    private void help(CommandSource source) {
        List<String> available=availablePassportCommands(source);
        source.sendMessage(Component.text("Passport 명령어",NamedTextColor.WHITE));
        for(String command:List.of("server","list","status","queue","web","link","player","tp","adminweb","announce")) {
            if(!available.contains(command)) continue;
            String usage=switch(command) {
                case "server" -> "/서버 [서버명]";
                case "list" -> "/passport list [페이지]";
                case "player","tp" -> "/passport "+command+" <실명|IGN>";
                case "announce" -> "/passport announce <내용>";
                default -> "/passport "+command;
            };
            String description=switch(command) {
                case "server" -> "서버 목록·이동"; case "status" -> "내 인증·플레이 기록";
                case "list" -> "네트워크 접속자·서버 위치";
                case "queue" -> "대기 순서·취소"; case "web" -> "웹페이지 열기";
                case "link" -> "계정 연결"; case "player" -> "플레이어 조회";
                case "tp" -> "플레이어에게 이동"; case "adminweb" -> "관리자 웹";
                default -> "전체 공지";
            };
            String executable=command.equals("server") ? "/서버" : "/passport "+command;
            ClickEvent click=Set.of("player","tp","announce").contains(command)
                ? ClickEvent.suggestCommand(executable+" ") : ClickEvent.runCommand(executable);
            source.sendMessage(Component.text(usage,NamedTextColor.GREEN).clickEvent(click)
                .append(Component.text(" · "+description,NamedTextColor.GRAY)));
        }
        if(source instanceof Player) source.sendMessage(Component.text("현재 서버의 다른 명령어는 /help",NamedTextColor.GRAY));
    }
    private void listPlayers(CommandSource source,String[] arguments,Policy viewer) {
        int requested;
        try { requested=NetworkRoster.pageNumber(arguments); }
        catch(IllegalArgumentException invalid) { source.sendMessage(Component.text("/passport list [페이지]",NamedTextColor.YELLOW)); return; }
        Instant now=Instant.now();
        boolean full=!(source instanceof Player) || canSuggestAdmin(source);
        var entries=proxy.getAllPlayers().stream().filter(Player::isActive).map(player -> {
            String server=player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
            String label=server==null ? null : policies.get(player.getUniqueId()).filter(policy -> policy.active(now))
                .map(policy -> policy.serverLabels().get(server)).orElse(null);
            return new NetworkRoster.Entry(player.getUsername(),server,label);
        }).toList();
        NetworkRoster.Page page;
        try { page=NetworkRoster.page(entries,requested); }
        catch(IllegalArgumentException invalid) { source.sendMessage(Component.text("해당 페이지가 없습니다. /passport list",NamedTextColor.YELLOW)); return; }
        source.sendMessage(Component.text("네트워크 접속 중 · "+page.total()+"명",NamedTextColor.WHITE)
            .append(Component.text("  "+page.number()+"/"+page.pages()+" 페이지",NamedTextColor.GRAY)));
        for(var entry:page.entries()) source.sendMessage(Component.text("· "+entry.ign(),NamedTextColor.WHITE)
            .append(Component.text(" · "+NetworkRoster.location(entry,viewer,full,waiting,now),NamedTextColor.GRAY)));
        if(page.entries().isEmpty()) source.sendMessage(Component.text("접속 중인 플레이어가 없습니다.",NamedTextColor.GRAY));
        if(page.number()<page.pages()) source.sendMessage(Component.text("[다음 페이지]",NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/passport list "+(page.number()+1))));
    }
    private void web(CommandSource source,boolean admin) {
        source.sendMessage(Component.text(admin ? "[Passport 관리자 웹]" : "[Passport 웹 열기]",NamedTextColor.AQUA)
            .clickEvent(ClickEvent.openUrl((admin ? adminOrigin : webOrigin).resolve("/").toString())));
    }
    private void adminAction(CommandSource source,Runnable action) {
        if(!(source instanceof Player player)) { action.run(); return; }
        Session session=sessions.get(player.getUniqueId());
        if(session==null || !current(session) || source.getPermissionValue("passport.admin")==Tristate.FALSE) { source.sendMessage(DENIED); return; }
        refreshes.fresh(player.getUniqueId()).whenComplete((policy,error) -> {
            if(!current(session)) return;
            if(error!=null || !policy.valid(Instant.now()) || !policy.administrator()) { source.sendMessage(Component.text("등록된 Passport 관리자만 사용할 수 있습니다.",NamedTextColor.RED)); return; }
            action.run();
        });
    }
    private List<Player> players(String query) {
        return sessions.values().stream().filter(this::current).filter(session ->
            session.player.getUsername().equalsIgnoreCase(query) || session.player.getUniqueId().toString().equalsIgnoreCase(query) || policies.get(session.player.getUniqueId())
                .filter(policy -> policy.active(Instant.now())).map(policy -> !policy.displayName().isBlank() && policy.displayName().equals(query)).orElse(false))
            .map(session -> session.player).sorted(Comparator.comparing(Player::getUsername)).toList();
    }
    private void playerLookup(CommandSource source,String query) {
        if(query.isBlank() || query.length()>80) { source.sendMessage(Component.text("/passport player <실명|Minecraft 닉네임|UUID>")); return; }
        api.players(query).whenComplete((response,error) -> {
            if(error!=null) { source.sendMessage(Component.text("플레이어 정보를 확인할 수 없습니다.",NamedTextColor.YELLOW)); return; }
            try {
                var found=response.getAsJsonArray("players");
                if(found.isEmpty()) { source.sendMessage(Component.text("플레이어를 찾을 수 없습니다.")); return; }
                if(found.size()>1) {
                    source.sendMessage(Component.text("검색 결과가 여러 명입니다. Minecraft 닉네임 또는 UUID로 지정하세요."));
                    for(var item:found) source.sendMessage(Component.text("· "+item.getAsJsonObject().get("minecraftName").getAsString())); return;
                }
                var match=found.get(0).getAsJsonObject(); UUID id=UUID.fromString(match.get("minecraftUuid").getAsString());
                Player online=proxy.getPlayer(id).orElse(null);
                String status="오프라인";
                if(online!=null) {
                    String serverLabel=source instanceof Player viewer ? online.getCurrentServer()
                        .flatMap(connection -> policies.get(viewer.getUniqueId()).flatMap(policy ->
                            CommandSelection.visibleServerLabel(policy,connection.getServerInfo().getName(),Instant.now())))
                        .orElse("") : "";
                    status=serverLabel.isBlank() ? "접속 중" : "접속 중 · "+serverLabel;
                }
                source.sendMessage(Component.text(match.get("minecraftName").getAsString()+" · "+match.get("displayName").getAsString()+" · "+status));
            } catch(RuntimeException malformed) { source.sendMessage(Component.text("플레이어 응답을 확인할 수 없습니다.",NamedTextColor.YELLOW)); }
        });
    }
    private void statusStatistics(Session session) {
        api.statistics(session.player.getUniqueId()).whenComplete((response,error) -> {
            if(!current(session)) return;
            if(error==null) try {
                if(response.get("available").getAsBoolean()) {
                    var totals=response.getAsJsonObject("totals"); long seconds=totals.get("playSeconds").getAsLong();
                    session.player.sendMessage(Component.text("총 플레이 "+(seconds/3600)+"시간 "+((seconds%3600)/60)+"분 · 사망 "+totals.get("deaths").getAsLong()+"회",NamedTextColor.GRAY));
                } else session.player.sendMessage(Component.text("집계된 플레이 기록이 없습니다.",NamedTextColor.GRAY));
            } catch(RuntimeException malformed) { session.player.sendMessage(Component.text("플레이 기록을 확인할 수 없습니다.",NamedTextColor.GRAY)); }
            else session.player.sendMessage(Component.text("플레이 기록을 확인할 수 없습니다.",NamedTextColor.GRAY));
            session.player.sendMessage(Component.text("[상세 기록 보기]",NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(webOrigin.resolve("/me/stats").toString())));
        });
    }
    private Player uniquePlayer(CommandSource source,String query) {
        List<Player> found=players(query);
        if(found.size()!=1) { source.sendMessage(Component.text(found.isEmpty() ? "접속 중인 플레이어를 찾을 수 없습니다." : "같은 이름이 여러 명입니다. Minecraft 닉네임으로 지정하세요.",NamedTextColor.YELLOW)); return null; }
        return found.getFirst();
    }
    private boolean commandReady(Session session) {
        long now=System.nanoTime(),previous=session.lastCommand.get();
        return now-previous>=TimeUnit.SECONDS.toNanos(1) && session.lastCommand.compareAndSet(previous,now);
    }
    private void transfer(Session session,String query) {
        if(session.connecting.get() || admissions.reservation(session.queueKey).isPresent()) {
            session.player.sendMessage(Component.text("서버 연결을 처리 중입니다. 잠시 후 다시 시도해주세요.",NamedTextColor.YELLOW)); return;
        }
        long generation=session.queueIntent.change();
        refreshes.fresh(session.player.getUniqueId()).whenComplete((policy,error) -> {
            if(!current(session) || generation!=session.queueIntent.generation()) return;
            if(error!=null || !policy.active(Instant.now())) { session.player.sendMessage(DENIED); return; }
            if(query.isBlank()) {
                if(policy.allowedServerIds().isEmpty()) session.player.sendMessage(Component.text("접속 가능한 서버가 없습니다."));
                else {
                    session.player.sendMessage(Component.text("접속 가능한 서버"));
                    policy.allowedServerIds().stream().sorted(Comparator.comparing(policy::label)).forEach(id -> {
                        String command="/서버 "+policy.commandName(id);
                        session.player.sendMessage(Component.text("· "+policy.label(id)+"  ").append(Component.text(command,NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand(command))));
                    });
                }
                return;
            }
            List<String> ids=CommandSelection.servers(policy,query);
            if(ids.size()!=1) { session.player.sendMessage(Component.text(ids.isEmpty() ? "접속 가능한 서버를 찾을 수 없습니다." : "같은 서버 이름이 여러 개입니다. 서버 목록에서 선택하세요.")); return; }
            String id=ids.getFirst();
            if(session.player.getCurrentServer().map(connection -> connection.getServerInfo().getName().equals(id)).orElse(false)) {
                admissions.cancel(session.queueKey); session.player.sendActionBar(Component.empty());
                session.player.sendMessage(Component.text("이미 접속 중인 서버입니다.")); return;
            }
            proxy.getServer(id).ifPresentOrElse(target -> {
                if(!policy.allows(id,Instant.now())) { session.player.sendMessage(DENIED); return; }
                session.queueIntent.resume();
                admitAndConnect(session,target,policy,null,generation).exceptionally(failure -> false);
            },() -> session.player.sendMessage(Component.text("현재 연결할 수 없는 서버입니다.",NamedTextColor.YELLOW)));
        });
    }
    private final class ServerShortcut implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            if(invocation.source() instanceof Player player) {
                Session session=sessions.get(player.getUniqueId()); if(session!=null && current(session) && commandReady(session)) transfer(session,String.join(" ",invocation.arguments()));
            }
        }
        @Override public List<String> suggest(Invocation invocation) { return serverSuggestions(invocation.source(),String.join(" ",invocation.arguments())); }
    }
    private boolean canSuggestAdmin(CommandSource source) {
        return availablePassportCommands(source).contains("player");
    }
    private List<String> playerSuggestions(CommandSource source,String prefix) {
        if(!canSuggestAdmin(source)) return List.of();
        List<String> names=new ArrayList<>();
        for(Session session:sessions.values()) if(current(session)) {
            names.add(session.player.getUsername());
            policies.get(session.player.getUniqueId()).filter(policy -> policy.active(Instant.now()))
                .map(Policy::displayName).filter(name -> !name.isBlank()).ifPresent(names::add);
        }
        return CommandSelection.suggestions(names,prefix);
    }
    private List<String> serverSuggestions(CommandSource source,String prefix) {
        return source instanceof Player player ? policies.get(player.getUniqueId()).filter(policy -> policy.active(Instant.now()))
            .map(policy -> CommandSelection.serverSuggestions(policy,prefix)).orElse(List.of()) : List.of();
    }
    @Subscribe(order=PostOrder.FIRST) public void departureMessage(PluginMessageEvent event) {
        if(DEPARTURE_CHANNEL.equals(event.getIdentifier())) event.setResult(PluginMessageEvent.ForwardResult.handled());
    }
    @Subscribe(order=PostOrder.FIRST) public void teleportReply(PluginMessageEvent event) {
        if(!TELEPORT_CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if(!ready || !(event.getSource() instanceof ServerConnection backend) || !(event.getTarget() instanceof Player player)
            || backend.getPlayer()!=player || player.getCurrentServer().orElse(null)!=backend) return;
        try {
            var message=TeleportMessage.decode(event.getData(),teleportSecret,Instant.now());
            teleports.accept(message,backend.getServerInfo().getName(),player.getUniqueId(),Instant.now());
        } catch(IllegalArgumentException ignored) { /* Untrusted packets never reach players or trigger actions. */ }
    }
    private void teleport(Session session,Player target) {
        if(session.player.getUniqueId().equals(target.getUniqueId())) { session.player.sendMessage(Component.text("자신에게 이동할 수 없습니다.")); return; }
        ServerConnection targetConnection=target.getCurrentServer().orElse(null);
        if(targetConnection==null) { session.player.sendMessage(Component.text("대상이 아직 서버에 입장하지 않았습니다.")); return; }
        String destination=targetConnection.getServerInfo().getName();
        if(session.connecting.get() || admissions.reservation(session.queueKey).isPresent()) { session.player.sendMessage(Component.text("서버 연결을 처리 중입니다.")); return; }
        long generation=session.queueIntent.change();
        refreshes.fresh(session.player.getUniqueId()).whenComplete((policy,error) -> {
            if(!current(session) || generation!=session.queueIntent.generation()) return;
            if(error!=null || !policy.administrator() || !policy.allows(destination,Instant.now())
                || session.player.getPermissionValue("passport.admin")==Tristate.FALSE) { session.player.sendMessage(DENIED); return; }
            var request=TeleportMessage.request(session.player.getUniqueId(),target.getUniqueId(),destination,Instant.now());
            var result=teleports.begin(request);
            if(result.isCompletedExceptionally()) { session.player.sendMessage(Component.text("이동을 처리 중입니다.")); return; }
            result.orTimeout(12,TimeUnit.SECONDS).whenComplete((status,failure) -> {
                if(current(session)) session.player.sendMessage(Component.text(failure==null && "ok".equals(status)
                    ? target.getUsername()+"님에게 이동했습니다." : "대상에게 이동하지 못했습니다. 접속 상태와 권한을 확인하세요."));
            });
            Runnable send=() -> {
                if(!current(session) || target.getCurrentServer().orElse(null)!=targetConnection) { teleports.fail(request.requestId()); return; }
                ServerConnection backend=session.player.getCurrentServer().orElse(null);
                if(backend==null || !backend.getServerInfo().getName().equals(destination)
                    || !backend.sendPluginMessage(TELEPORT_CHANNEL,request.encode(teleportSecret))) teleports.fail(request.requestId());
            };
            if(session.player.getCurrentServer().map(connection -> connection.getServerInfo().getName().equals(destination)).orElse(false)) { admissions.cancel(session.queueKey); send.run(); }
            else admitAndConnect(session,targetConnection.getServer(),policy,null,generation)
                .whenComplete((connected,failure) -> {
                    if(failure!=null || !Boolean.TRUE.equals(connected)) teleports.fail(request.requestId());
                    else proxy.getScheduler().buildTask(this,send).delay(Duration.ofMillis(300)).schedule();
                });
        });
    }
    private final class PassportCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            String[] args=invocation.arguments();
            String command=args.length==0 ? "link" : args[0].toLowerCase(Locale.ROOT);
            if(command.equals("help")) { help(invocation.source()); return; }
            if(command.equals("list") && !(invocation.source() instanceof Player)) { listPlayers(invocation.source(),args,null); return; }
            if(Set.of("player","adminweb","tp","announce").contains(command)) {
                adminAction(invocation.source(),() -> {
                    CommandSource source=invocation.source(); String value=String.join(" ",Arrays.copyOfRange(args,1,args.length));
                    if(command.equals("adminweb")) { web(source,true); return; }
                    if(command.equals("announce")) {
                        if(!CommandSelection.safeAnnouncement(value)) { source.sendMessage(Component.text("/passport announce <공지 내용: 1–300자>")); return; }
                        proxy.sendMessage(Component.text("[전체 공지] ",NamedTextColor.GOLD).append(Component.text(value,NamedTextColor.WHITE))); return;
                    }
                    if(command.equals("player")) { playerLookup(source,value); return; }
                    Player target=uniquePlayer(source,value); if(target==null) return;
                    if(!(source instanceof Player player)) { source.sendMessage(Component.text("게임 내에서 사용하세요.")); return; }
                    Session session=sessions.get(player.getUniqueId()); if(session==null || !current(session)) return;
                    if(commandReady(session)) teleport(session,target);
                }); return;
            }
            if (!(invocation.source() instanceof Player player)) { invocation.source().sendMessage(Component.text("게임 내에서 사용하세요.")); return; }
            Session session=sessions.get(player.getUniqueId());
            if (session==null || !current(session)) { player.sendMessage(DENIED); return; }
            long now=System.nanoTime(), previous=session.lastCommand.get();
            if (now-previous<TimeUnit.SECONDS.toNanos(1) || !session.lastCommand.compareAndSet(previous,now)) return;
            String sub=invocation.arguments().length==0 ? "link" : invocation.arguments()[0].toLowerCase(Locale.ROOT);
            switch(sub) {
                case "link" -> createLink(session);
                case "web" -> web(player,false);
                case "list" -> refreshes.fresh(player.getUniqueId()).whenComplete((policy,error) -> {
                    if(!current(session)) return;
                    if(error!=null || !policy.active(Instant.now())) { player.sendMessage(DENIED); return; }
                    listPlayers(player,args,policy);
                });
                case "server" -> transfer(session,String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                case "queue" -> {
                    if(args.length==1) queueStatus(session,true);
                    else if(args.length==2 && args[1].equalsIgnoreCase("leave")) leaveQueue(session);
                    else player.sendMessage(Component.text("/passport queue [leave]"));
                }
                case "status" -> refresh(session).whenComplete((valid,error) -> {
                    if(!current(session)) return;
                    if(error!=null || !Boolean.TRUE.equals(valid)) { player.sendMessage(DENIED); return; }
                    policies.get(player.getUniqueId()).ifPresent(policy -> player.sendMessage(Component.text("Passport · "+(policy.active(Instant.now()) ? "인증 완료" : "인증 필요")+" · 접속 가능 서버 "+policy.allowedServerIds().size()+"개")));
                    statusStatistics(session);
                    queueStatus(session,false);
                    session.routing.retryManually();
                    moveDefault(session);
                });
                default -> help(player);
            }
        }
        @Override public List<String> suggest(Invocation invocation) {
            String[] args=invocation.arguments();
            List<String> commands=availablePassportCommands(invocation.source());
            if(args.length>1 && !commands.contains(args[0].toLowerCase(Locale.ROOT))) return List.of();
            if(args.length>1 && args[0].equalsIgnoreCase("server")) return serverSuggestions(invocation.source(),String.join(" ",Arrays.copyOfRange(args,1,args.length)));
            if(args.length==2 && Set.of("player","tp").contains(args[0].toLowerCase(Locale.ROOT)))
                return playerSuggestions(invocation.source(),args[1]);
            if(args.length==2 && args[0].equalsIgnoreCase("queue")) return CommandSelection.suggestions(List.of("leave"),args[1]);
            if(args.length>1) return List.of();
            String prefix=args.length==0 ? "" : args[0].toLowerCase(Locale.ROOT); return commands.stream().filter(command -> command.startsWith(prefix)).toList();
        }
    }
}
