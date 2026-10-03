package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Single-proxy FIFO and slot reservations. Occupancy is sampled inside the same admission lock. */
public final class AdmissionQueue {
    public record Key(UUID uuid,String session) {}
    public record Ticket(long id,Key key,String server) {}
    public record Reservation(long id,Key key,String server,boolean bypass,long started) {}
    public record Position(Ticket ticket,int position,int total) {}
    public enum Status { ALLOWED, QUEUED, DENIED, BUSY, STALE, FULL }
    public record Decision(Status status,Reservation reservation,boolean joined) {}
    private final Map<String,Integer> capacities;
    private final Function<String,Set<Key>> occupants;
    private final Map<Key,Ticket> tickets=new HashMap<>();
    private final Map<String,LinkedHashMap<Key,Ticket>> queues=new HashMap<>();
    private final Map<Key,Reservation> reservations=new HashMap<>();
    private final Set<Long> started=new HashSet<>();
    private final Map<String,Long> retryAfter=new HashMap<>();
    private long sequence;
    public AdmissionQueue(Map<String,Integer> capacities,Function<String,Set<Key>> occupants) {
        this.capacities=Map.copyOf(capacities); this.occupants=occupants;
        capacities.keySet().forEach(server -> queues.put(server,new LinkedHashMap<>()));
    }
    public static boolean bypass(Policy policy,UUID uuid,String server,boolean locallyDenied,Instant now) {
        return !locallyDenied && policy!=null && policy.minecraftUuid().equals(uuid)
            && policy.administrator() && policy.allows(server,now);
    }
    public synchronized Decision request(Key key,String server,Policy policy,boolean locallyDenied,Instant now,long clock) {
        if(!capacities.containsKey(server) || policy==null || !key.uuid().equals(policy.minecraftUuid()) || !policy.allows(server,now))
            return new Decision(Status.DENIED,null,false);
        return reserve(key,server,bypass(policy,key.uuid(),server,locallyDenied,now),false,clock);
    }
    public synchronized Decision promote(Ticket ticket,Policy policy,boolean locallyDenied,Instant now,long clock) {
        if(!ticket.equals(tickets.get(ticket.key()))) return new Decision(Status.STALE,null,false);
        return request(ticket.key(),ticket.server(),policy,locallyDenied,now,clock);
    }
    /** Limbo admits unverified users but never queues a player who has no playable backend. */
    public synchronized Decision waiting(Key key,String server,long clock) {
        if(!capacities.containsKey(server)) return new Decision(Status.DENIED,null,false);
        return reserve(key,server,false,true,clock);
    }
    private Decision reserve(Key key,String server,boolean bypass,boolean fallback,long clock) {
        Set<Key> online=Set.copyOf(occupants.apply(server));
        reconcile(server,online);
        Reservation existing=reservations.get(key);
        if(existing!=null) {
            if(!existing.server().equals(server) || started.contains(existing.id())) return new Decision(Status.BUSY,null,false);
            if(!existing.bypass() || bypass) return new Decision(Status.ALLOWED,existing,false);
            // A fresh administrator downgrade invalidates an earlier full-server exemption.
            reservations.remove(key);
        }
        if(online.contains(key)) return new Decision(Status.ALLOWED,null,false);
        if(!fallback) {
            Ticket old=tickets.get(key);
            if(old!=null && !old.server().equals(server)) cancel(old);
        }
        LinkedHashMap<Key,Ticket> queue=queues.get(server);
        boolean turn=queue.isEmpty() || queue.firstEntry().getKey().equals(key);
        if(!bypass && (!turn || used(server,online)>=capacities.get(server) || clock<retryAfter.getOrDefault(server,Long.MIN_VALUE))) {
            if(fallback) return new Decision(Status.FULL,null,false);
            boolean joined=!tickets.containsKey(key);
            if(joined) { Ticket ticket=new Ticket(++sequence,key,server); tickets.put(key,ticket); queue.put(key,ticket); }
            return new Decision(Status.QUEUED,null,joined);
        }
        if(!fallback) cancel(key);
        Reservation reservation=new Reservation(++sequence,key,server,bypass,clock);
        reservations.put(key,reservation);
        return new Decision(Status.ALLOWED,reservation,true);
    }
    private int used(String server,Set<Key> online) {
        return online.size()+(int)reservations.values().stream().filter(r -> r.server().equals(server) && !online.contains(r.key())).count();
    }
    private void reconcile(String server,Set<Key> online) {
        for(Reservation reservation:List.copyOf(reservations.values()))
            if(reservation.server().equals(server) && online.contains(reservation.key())) release(reservation,false,0);
    }
    public synchronized void arrived(Key key,String server) {
        reconcile(server,Set.copyOf(occupants.apply(server)));
        Ticket ticket=tickets.get(key); if(ticket!=null && ticket.server().equals(server)) cancel(ticket);
    }
    public synchronized Optional<Position> position(Key key) {
        Ticket ticket=tickets.get(key); if(ticket==null) return Optional.empty();
        int index=1;
        for(Key queued:queues.get(ticket.server()).keySet()) { if(queued.equals(key)) break; index++; }
        return Optional.of(new Position(ticket,index,queues.get(ticket.server()).size()));
    }
    public synchronized boolean canAttempt(Ticket ticket,boolean bypass,long clock) {
        if(!ticket.equals(tickets.get(ticket.key())) || reservations.containsKey(ticket.key())) return false;
        Set<Key> online=Set.copyOf(occupants.apply(ticket.server())); reconcile(ticket.server(),online);
        return bypass || (clock>=retryAfter.getOrDefault(ticket.server(),Long.MIN_VALUE)
            && queues.get(ticket.server()).firstEntry().getValue().equals(ticket) && used(ticket.server(),online)<capacities.get(ticket.server()));
    }
    public synchronized boolean cancel(Ticket ticket) {
        if(!tickets.remove(ticket.key(),ticket)) return false;
        queues.get(ticket.server()).remove(ticket.key(),ticket); return true;
    }
    public synchronized boolean cancel(Key key) { Ticket ticket=tickets.get(key); return ticket!=null && cancel(ticket); }
    /** Restore a failed promotion in its original FIFO position, after its connection has terminated.
     * The caller must first recheck the current session, selection generation and access policy. */
    public synchronized boolean restore(Ticket ticket,long clock) {
        if(ticket==null || !capacities.containsKey(ticket.server()) || tickets.containsKey(ticket.key())
            || occupants.apply(ticket.server()).contains(ticket.key())) return false;
        Reservation pending=reservations.get(ticket.key());
        if(pending!=null && pending.server().equals(ticket.server())) return false;
        tickets.put(ticket.key(),ticket);
        LinkedHashMap<Key,Ticket> queue=queues.get(ticket.server());
        List<Ticket> ordered=new ArrayList<>(queue.values()); ordered.add(ticket);
        ordered.sort(Comparator.comparingLong(Ticket::id));
        queue.clear(); ordered.forEach(value -> queue.put(value.key(),value));
        retryAfter.merge(ticket.server(),clock+TimeUnit.SECONDS.toNanos(5),Math::max);
        return true;
    }
    public synchronized Optional<Reservation> reservation(Key key) { return Optional.ofNullable(reservations.get(key)); }
    /** Only one PreConnect event may initiate the physical connection behind a reserved slot. */
    public synchronized boolean begin(Reservation reservation) {
        return reservation==null || reservation.equals(reservations.get(reservation.key())) && started.add(reservation.id());
    }
    public synchronized boolean releaseUnstarted(Reservation reservation) {
        return reservation!=null && !started.contains(reservation.id()) && release(reservation,false,0);
    }
    public synchronized boolean release(Reservation reservation,boolean failed,long clock) {
        if(reservation==null || !reservations.remove(reservation.key(),reservation)) return false;
        started.remove(reservation.id());
        if(failed) retryAfter.put(reservation.server(),clock+TimeUnit.SECONDS.toNanos(5));
        return true;
    }
    public synchronized void failed(Key key,String server,long clock) {
        Reservation reservation=reservations.get(key);
        if(reservation!=null && reservation.server().equals(server)) release(reservation,true,clock);
    }
    public synchronized void disconnected(Key key) { cancel(key); release(reservations.get(key),false,0); }
    /** The caller must disconnect these sessions before removing their reservations: late connects must not overfill. */
    public synchronized List<Reservation> overdue(long clock) {
        return reservations.values().stream().filter(r -> clock-r.started()>=TimeUnit.SECONDS.toNanos(45)).toList();
    }
}
