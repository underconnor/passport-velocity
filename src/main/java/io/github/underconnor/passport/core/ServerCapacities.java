package io.github.underconnor.passport.core;

import java.util.*;

/** Explicit capacities prevent newly registered or misspelled servers from silently becoming unlimited. */
public final class ServerCapacities {
    private ServerCapacities() {}
    public static Map<String,Integer> parse(String value,Set<String> registered) {
        if(value==null || value.isBlank()) throw new IllegalArgumentException("PASSPORT_SERVER_CAPACITIES is required");
        Map<String,Integer> capacities=new HashMap<>();
        for(String entry:value.split(",",-1)) {
            String[] parts=entry.trim().split("=",-1);
            if(parts.length!=2 || !parts[0].matches("[a-z][a-z0-9_-]{0,63}") || !parts[1].matches("[1-9][0-9]{0,3}"))
                throw new IllegalArgumentException("Invalid server capacity");
            int capacity=Integer.parseInt(parts[1]);
            if(capacity>1000 || capacities.put(parts[0],capacity)!=null) throw new IllegalArgumentException("Invalid server capacity");
        }
        if(!capacities.keySet().equals(registered)) throw new IllegalArgumentException("Every registered server must have exactly one capacity");
        return Map.copyOf(capacities);
    }
}
