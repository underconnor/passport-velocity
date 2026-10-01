package io.github.underconnor.passport.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
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
    private PolicyRefreshes refreshes;
    private PolicyEventPoller eventPoller;
    private volatile boolean ready;
    private String waiting, defaultServer;
    private URI webOrigin;
    private Set<String> sensitive = Set.of();
    private static final Component DENIED = Component.text("Passport 인증 또는 서버 권한을 확인할 수 없습니다.", NamedTextColor.RED);
    private static final class Session {
        final Player player;
        final String gameSession = UUID.randomUUID().toString();
        final AtomicBoolean refreshing = new AtomicBoolean(), linking = new AtomicBoolean(), moving = new AtomicBoolean();
        final AtomicLong lastCommand = new AtomicLong(), linkGeneration = new AtomicLong();
        final RoutingAttempts routing = new RoutingAttempts();
        volatile LinkCompletionPoller completion;
        volatile String linkId;
        volatile Instant linkExpiry;
        volatile long nextRefresh;
        Session(Player player) { this.player = player; }
    }
    @Inject public PassportVelocity(ProxyServer proxy, Logger logger) { this.proxy = proxy; this.logger = logger; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("passport").plugin(this).build(), new PassportCommand());
        try {
            waiting = ApiClient.env("PASSPORT_WAITING_SERVER", "passport-limbo");
            defaultServer = ApiClient.env("PASSPORT_DEFAULT_SERVER", "lobby");
            if (!proxy.getConfiguration().isOnlineMode()) throw new IllegalArgumentException("Velocity online-mode must be true");
            if (proxy.getServer(waiting).isEmpty() || waiting.equals(defaultServer)) throw new IllegalArgumentException("A separate waiting server must exist");
            webOrigin = URI.create(ApiClient.env("PASSPORT_WEB_ORIGIN", "https://passport.example"));
            if (webOrigin.getHost() == null || webOrigin.getUserInfo() != null || !("https".equals(webOrigin.getScheme()) || ("http".equals(webOrigin.getScheme()) && Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP", "false"))))) throw new IllegalArgumentException("Invalid web origin");
            sensitive = new HashSet<>(Arrays.asList(ApiClient.env("PASSPORT_SENSITIVE_SERVERS", "").split(",")));
            api = new ApiClient(ApiClient.env("PASSPORT_API_BASE_URL", "https://api.passport.example/"),
                System.getenv("API_SERVICE_TOKEN"), Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP", "false")));
            refreshes = new PolicyRefreshes(uuid -> api.policy(uuid).thenApply(policy -> {
                if (!policies.acceptOrCurrent(policy)) throw new CompletionException(new IllegalStateException("Stale policy response"));
                return policy;
            }));
            eventPoller = new PolicyEventPoller(api::events,
                () -> sessions.values().stream().filter(this::current).map(s -> s.player.getUniqueId()).collect(java.util.stream.Collectors.toSet()),
                this::refreshFromEvent);
            ready = true;
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
        sessions.put(player.getUniqueId(), session);
    }
    @Subscribe(order=PostOrder.LAST) public EventTask initial(PlayerChooseInitialServerEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (!ready || session == null || !current(session)) { event.getPlayer().disconnect(DENIED); return null; }
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
        if (!ready || session == null || !current(session)) { event.setResult(ServerPreConnectEvent.ServerResult.denied()); return null; }
        String serverId = target.getServerInfo().getName();
        if (serverId.equals(waiting)) return null;
        if (!sensitive.contains(serverId) && policies.allows(event.getPlayer().getUniqueId(), serverId, Instant.now())) return null;
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return EventTask.resumeWhenComplete(refresh(session).handle((valid, error) -> {
            if (error == null && Boolean.TRUE.equals(valid) && current(session)
                && policies.allows(session.player.getUniqueId(), serverId, Instant.now()))
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
            else if (current(session)) session.player.sendMessage(DENIED);
            return null;
        }));
    }
    @Subscribe public void connected(ServerConnectedEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
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
    @Subscribe(order=PostOrder.LAST) public void kicked(KickedFromServerEvent event) {
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
        if (session != null && session.player == event.getPlayer() && sessions.remove(event.getPlayer().getUniqueId(), session)) cancel(session);
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { ready = false; if (api != null) api.close(); }
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
        for (Session session : sessions.values()) {
            if (!current(session)) continue;
            enforce(session);
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
        if (!current(session)) return;
        String currentServer = session.player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        RegisteredServer target = policies.get(session.player.getUniqueId())
            .flatMap(policy -> AutomaticRouting.target(policy, currentServer, waiting, defaultServer, Instant.now()))
            .flatMap(proxy::getServer).orElse(null);
        if (target == null) return;
        long attempt = session.routing.begin(System.nanoTime());
        if (attempt == 0) return;
        try {
            session.player.createConnectionRequest(target).connect().orTimeout(3,TimeUnit.SECONDS).whenComplete((result,error) -> {
                if (session.routing.complete(attempt,error == null && result.isSuccessful(),System.nanoTime()) && current(session)) routingFailure(session);
            });
        } catch (RuntimeException error) {
            if (session.routing.complete(attempt,false,System.nanoTime())) routingFailure(session);
        }
    }
    private void routingFailure(Session session) {
        if (current(session)) session.player.sendMessage(Component.text("로비에 연결하지 못했습니다. 잠시 자동 재시도하며, 계속 실패하면 /passport status로 다시 확인하세요.",NamedTextColor.YELLOW));
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
                session.player.sendMessage(Component.text("계정 연결이 완료되었습니다. 로비로 자동 이동합니다.",NamedTextColor.GREEN));
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
                    session.player.sendMessage(Component.text("[u-SAINT 인증하기]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url.toString())));
                    session.player.sendMessage(Component.text("게임 접속을 유지한 채 웹에서 계정 연결을 확인하면 로비로 자동 이동합니다. 취소: /passport cancel",NamedTextColor.GRAY));
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
    private final class PassportCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) { invocation.source().sendMessage(Component.text("게임 내에서 사용하세요.")); return; }
            Session session=sessions.get(player.getUniqueId());
            if (session==null || !current(session)) { player.sendMessage(DENIED); return; }
            long now=System.nanoTime(), previous=session.lastCommand.get();
            if (now-previous<TimeUnit.SECONDS.toNanos(1) || !session.lastCommand.compareAndSet(previous,now)) return;
            String sub=invocation.arguments().length==0 ? "link" : invocation.arguments()[0].toLowerCase(Locale.ROOT);
            switch(sub) {
                case "link" -> createLink(session);
                case "cancel" -> cancel(session).whenComplete((v,e) -> { if(current(session)) player.sendMessage(Component.text(e==null ? "연결 요청을 취소했습니다." : "취소 확인에 실패했습니다. 연결 요청은 최대 5분 후 만료됩니다.")); });
                case "confirm" -> {
                    String id=session.linkId;
                    if(id==null || session.linkExpiry==null || !session.linkExpiry.isAfter(Instant.now())) { player.sendMessage(Component.text("유효한 연결 요청이 없습니다. /passport 로 시작하세요.")); return; }
                    LinkCompletionPoller completion = session.completion;
                    if (completion == null || completion.stopped()) {
                        player.sendMessage(Component.text("연결 상태는 /passport status로 확인하세요. 새 연결은 /passport 로 시작합니다."));
                        return;
                    }
                    completion.confirmManually();
                }
                case "status" -> refresh(session).whenComplete((valid,error) -> {
                    if(!current(session)) return;
                    if(error!=null || !Boolean.TRUE.equals(valid)) { player.sendMessage(DENIED); return; }
                    policies.get(player.getUniqueId()).ifPresent(policy -> player.sendMessage(Component.text("Passport: "+policy.status()+" | 허용 서버: "+String.join(", ",policy.allowedServerIds()))));
                    session.routing.retryManually();
                    moveDefault(session);
                });
                default -> player.sendMessage(Component.text("/passport [status|confirm|cancel]"));
            }
        }
        @Override public List<String> suggest(Invocation invocation) { return List.of("status","confirm","cancel"); }
    }
}
