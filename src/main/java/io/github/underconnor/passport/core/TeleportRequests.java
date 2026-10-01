package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One in-flight teleport per actor; only a matching destination response completes it. */
public final class TeleportRequests {
    private record Pending(TeleportMessage request,CompletableFuture<String> result) {}
    private final Map<UUID,Pending> pending=new HashMap<>();
    public synchronized CompletableFuture<String> begin(TeleportMessage request) {
        if(pending.size()>=128 || pending.values().stream().anyMatch(value -> value.request.actor().equals(request.actor())))
            return CompletableFuture.failedFuture(new IllegalStateException("Teleport already pending"));
        var result=new CompletableFuture<String>(); pending.put(request.requestId(),new Pending(request,result));
        result.whenComplete((value,error) -> { synchronized(this) { pending.remove(request.requestId()); } });
        return result;
    }
    public synchronized boolean accept(TeleportMessage message,String sourceServer,UUID carrier,Instant now) {
        Pending value=pending.get(message.requestId());
        if(value==null || !message.kind().equals("RESULT") || !message.valid(now)
            || !sourceServer.equals(message.serverId()) || !carrier.equals(message.actor())
            || !value.request.reply(message.result()).equals(message)) return false;
        return value.result.complete(message.result());
    }
    public synchronized void fail(UUID requestId) {
        Pending value=pending.get(requestId); if(value!=null) value.result.complete("unavailable");
    }
    public synchronized void close() {
        for(Pending value:List.copyOf(pending.values())) value.result.complete("unavailable");
    }
}
