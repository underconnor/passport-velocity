package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.*;

public final class NetworkRoster {
    public static final int PAGE_SIZE=20;
    public record Entry(String ign,String serverId,String label) {}
    public record Page(int total,int number,int pages,List<Entry> entries) {}
    private NetworkRoster() {}
    public static int pageNumber(String[] arguments) {
        if(arguments.length==1) return 1;
        if(arguments.length!=2 || !arguments[1].matches("[1-9][0-9]{0,5}")) throw new IllegalArgumentException("Page number");
        return Integer.parseInt(arguments[1]);
    }
    public static Page page(Collection<Entry> entries,int page) {
        List<Entry> sorted=entries.stream().sorted(Comparator.comparing(Entry::ign,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::ign)).toList();
        int pages=Math.max(1,(sorted.size()+PAGE_SIZE-1)/PAGE_SIZE);
        if(page<1 || page>pages) throw new IllegalArgumentException("Page out of range");
        int start=(page-1)*PAGE_SIZE;
        return new Page(sorted.size(),page,pages,List.copyOf(sorted.subList(start,Math.min(sorted.size(),start+PAGE_SIZE))));
    }
    public static String location(Entry entry,Policy viewer,boolean full,String waiting,Instant now) {
        if(entry.serverId()==null) return "연결 중";
        if(!full && (viewer==null || !viewer.allows(entry.serverId(),now))) return "비공개 서버";
        if(entry.serverId().equals(waiting)) return "인증 대기실";
        // Never fall back to deployment IDs; a lease may be absent while a connection is being refreshed.
        String label=full ? entry.label() : viewer.serverLabels().get(entry.serverId());
        return label==null || label.isBlank() ? "서버 정보 확인 중" : label;
    }
}
