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
    private static final MinecraftChannelIdentifier TELEPORT_CHANNEL=MinecraftChannelIdentifier.from(TeleportMessage.CHANNEL);
    private PolicyRefreshes refreshes;
    private PolicyEventPoller eventPoller;
    private ServerHeartbeat heartbeat;
    private volatile boolean ready;
    private String waiting, defaultServer;
    private URI webOrigin, adminOrigin;
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
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("서버").plugin(this).build(), new ServerShortcut());
        var builtin=proxy.getCommandManager().getCommandMeta("server");
        if(builtin!=null) proxy.getCommandManager().unregister(builtin);
        proxy.getCommandManager().unregister("velocity:server");
        try {
            waiting = ApiClient.env("PASSPORT_WAITING_SERVER", "passport-limbo");
            defaultServer = ApiClient.env("PASSPORT_DEFAULT_SERVER", "lobby");
            if (!proxy.getConfiguration().isOnlineMode()) throw new IllegalArgumentException("Velocity online-mode must be true");
            if (proxy.getServer(waiting).isEmpty() || waiting.equals(defaultServer)) throw new IllegalArgumentException("A separate waiting server must exist");
            webOrigin = URI.create(ApiClient.env("PASSPORT_WEB_ORIGIN", "https://passport.example"));
            if (webOrigin.getHost() == null || webOrigin.getUserInfo() != null || !("https".equals(webOrigin.getScheme()) || ("http".equals(webOrigin.getScheme()) && Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP", "false"))))) throw new IllegalArgumentException("Invalid web origin");
            adminOrigin=URI.create(ApiClient.env("PASSPORT_ADMIN_ORIGIN","https://admin-overworld.flyjung.kr"));
            if(!"https".equals(adminOrigin.getScheme()) || adminOrigin.getHost()==null || adminOrigin.getUserInfo()!=null || adminOrigin.getQuery()!=null || adminOrigin.getFragment()!=null) throw new IllegalArgumentException("Invalid admin origin");
            api = new ApiClient(ApiClient.env("PASSPORT_API_BASE_URL", "https://api.passport.example/"),
                System.getenv("API_SERVICE_TOKEN"), Boolean.parseBoolean(ApiClient.env("PASSPORT_ALLOW_INSECURE_HTTP", "false")));
            teleportSecret=TeleportSecrets.resolve(System.getenv("PASSPORT_TELEPORT_SECRET"),System.getenv("API_SERVICE_TOKEN"));
            proxy.getChannelRegistrar().register(TELEPORT_CHANNEL);
            refreshes = new PolicyRefreshes(uuid -> api.policy(uuid).thenApply(policy -> {
                if (!policies.acceptOrCurrent(policy)) throw new CompletionException(new IllegalStateException("Stale policy response"));
                return policy;
            }));
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
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return EventTask.resumeWhenComplete(refreshes.fresh(session.player.getUniqueId()).handle((policy, error) -> {
            if (error == null && current(session)
                && policies.allows(session.player.getUniqueId(), serverId, Instant.now()))
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
            else if (current(session) && session.player.getCurrentServer().isEmpty()) {
                // An initial pre-connect denial need not fire a backend kick event.
                proxy.getServer(waiting).ifPresent(server -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(server)));
            } else if (current(session) && !(serverId.equals(defaultServer) && session.routing.inProgress()))
                session.player.sendMessage(DENIED);
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
    @Subscribe public void shutdown(ProxyShutdownEvent event) { ready = false; teleports.close(); proxy.getChannelRegistrar().unregister(TELEPORT_CHANNEL); if (api != null) api.close(); }
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
        if(event.getCommandSource() instanceof Player && CommandSelection.blockedBuiltin(event.getCommand())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            event.getCommandSource().sendMessage(Component.text("/서버 <서버명>",NamedTextColor.YELLOW));
        }
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
                String status=online==null ? "오프라인" : "접속 중 · "+online.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("접속 중");
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
        refreshes.fresh(session.player.getUniqueId()).whenComplete((policy,error) -> {
            if(!current(session)) return;
            if(error!=null || !policy.active(Instant.now())) { session.player.sendMessage(DENIED); return; }
            if(query.isBlank()) {
                List<String> labels=policy.allowedServerIds().stream().sorted().map(id -> policy.label(id)+" ("+id+")").toList();
                session.player.sendMessage(Component.text(labels.isEmpty() ? "접속 가능한 서버가 없습니다." : "접속 가능한 서버: "+String.join(", ",labels))); return;
            }
            List<String> ids=CommandSelection.servers(policy,query);
            if(ids.size()!=1) { session.player.sendMessage(Component.text(ids.isEmpty() ? "접속 가능한 서버를 찾을 수 없습니다." : "같은 서버 이름이 여러 개입니다. 서버 ID로 지정하세요.")); return; }
            String id=ids.getFirst();
            if(session.player.getCurrentServer().map(connection -> connection.getServerInfo().getName().equals(id)).orElse(false)) {
                session.player.sendMessage(Component.text("이미 접속 중인 서버입니다.")); return;
            }
            proxy.getServer(id).ifPresentOrElse(target -> {
                if(!policy.allows(id,Instant.now())) { session.player.sendMessage(DENIED); return; }
                session.player.createConnectionRequest(target).connect().orTimeout(3,TimeUnit.SECONDS).whenComplete((result,failure) -> {
                    if(current(session) && (failure!=null || !result.isSuccessful())) session.player.sendMessage(Component.text("서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.",NamedTextColor.YELLOW));
                });
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
        return !(source instanceof Player player) || source.getPermissionValue("passport.admin")!=Tristate.FALSE
            && sessions.containsKey(player.getUniqueId()) && policies.get(player.getUniqueId())
                .filter(policy -> policy.active(Instant.now()) && policy.administrator()).isPresent();
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
            .map(policy -> {
                List<String> names=new ArrayList<>(policy.allowedServerIds());
                for(String id:policy.allowedServerIds()) if(!policy.label(id).contains(" ")) names.add(policy.label(id));
                return CommandSelection.suggestions(names,prefix);
            }).orElse(List.of()) : List.of();
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
        refreshes.fresh(session.player.getUniqueId()).whenComplete((policy,error) -> {
            if(!current(session)) return;
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
            if(session.player.getCurrentServer().map(connection -> connection.getServerInfo().getName().equals(destination)).orElse(false)) send.run();
            else session.player.createConnectionRequest(targetConnection.getServer()).connect().orTimeout(4,TimeUnit.SECONDS)
                .whenComplete((connection,failure) -> {
                    if(failure!=null || !connection.isSuccessful()) teleports.fail(request.requestId());
                    else proxy.getScheduler().buildTask(this,send).delay(Duration.ofMillis(300)).schedule();
                });
        });
    }
    private final class PassportCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            String[] args=invocation.arguments();
            String command=args.length==0 ? "link" : args[0].toLowerCase(Locale.ROOT);
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
                case "server" -> transfer(session,String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                case "status" -> refresh(session).whenComplete((valid,error) -> {
                    if(!current(session)) return;
                    if(error!=null || !Boolean.TRUE.equals(valid)) { player.sendMessage(DENIED); return; }
                    policies.get(player.getUniqueId()).ifPresent(policy -> player.sendMessage(Component.text("Passport · "+(policy.active(Instant.now()) ? "인증 완료" : "인증 필요")+" · 접속 가능 서버 "+policy.allowedServerIds().size()+"개")));
                    statusStatistics(session);
                    session.routing.retryManually();
                    moveDefault(session);
                });
                default -> player.sendMessage(Component.text("/passport [server|status|web]"));
            }
        }
        @Override public List<String> suggest(Invocation invocation) {
            String[] args=invocation.arguments();
            if(args.length>1 && args[0].equalsIgnoreCase("server")) return serverSuggestions(invocation.source(),String.join(" ",Arrays.copyOfRange(args,1,args.length)));
            if(args.length==2 && Set.of("player","tp").contains(args[0].toLowerCase(Locale.ROOT)))
                return playerSuggestions(invocation.source(),args[1]);
            if(args.length>1) return List.of();
            List<String> commands=new ArrayList<>(List.of("server","status","web","link"));
            if(canSuggestAdmin(invocation.source())) commands.addAll(List.of("player","adminweb","tp","announce"));
            String prefix=args.length==0 ? "" : args[0].toLowerCase(Locale.ROOT); return commands.stream().filter(command -> command.startsWith(prefix)).toList();
        }
    }
}
