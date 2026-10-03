package io.github.underconnor.passport.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** A per-backend-connection nonce prevents a delayed departure from affecting a later visit. */
public record DepartureMessage(String kind,UUID connectionId,UUID actor,String serverId,long expiresAt) {
    public static final String CHANNEL="passport:departure";
    private static final String DOMAIN="passport.departure.v1";
    public DepartureMessage {
        Objects.requireNonNull(connectionId); Objects.requireNonNull(actor);
        if(!Set.of("BEGIN","TRANSFER").contains(kind) || serverId==null || !serverId.matches("[a-z][a-z0-9_-]{0,63}") || expiresAt<=0)
            throw new IllegalArgumentException("Invalid departure message");
    }
    public static DepartureMessage begin(UUID actor,String serverId,Instant now) {
        return new DepartureMessage("BEGIN",UUID.randomUUID(),actor,serverId,now.plusSeconds(10).toEpochMilli());
    }
    public DepartureMessage transferred(Instant now) {
        return new DepartureMessage("TRANSFER",connectionId,actor,serverId,now.plusSeconds(10).toEpochMilli());
    }
    public boolean valid(Instant now) { long time=now.toEpochMilli(); return expiresAt>time && expiresAt<=time+10_000; }
    public byte[] encode(String secret) {
        try {
            var buffer=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(buffer)) {
                out.writeUTF(DOMAIN); out.writeUTF(kind); uuid(out,connectionId); uuid(out,actor); out.writeUTF(serverId); out.writeLong(expiresAt);
            }
            byte[] body=buffer.toByteArray(),signature=mac(body,secret),message=Arrays.copyOf(body,body.length+signature.length);
            System.arraycopy(signature,0,message,body.length,signature.length); return message;
        } catch(IOException error) { throw new IllegalStateException(error); }
    }
    public static DepartureMessage decode(byte[] bytes,String secret,Instant now) {
        if(bytes==null || bytes.length<90 || bytes.length>256) throw new IllegalArgumentException("Departure size");
        byte[] body=Arrays.copyOf(bytes,bytes.length-32),signature=Arrays.copyOfRange(bytes,bytes.length-32,bytes.length);
        if(!MessageDigest.isEqual(mac(body,secret),signature)) throw new IllegalArgumentException("Departure signature");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
            if(!DOMAIN.equals(in.readUTF())) throw new IllegalArgumentException("Departure version");
            var message=new DepartureMessage(in.readUTF(),uuid(in),uuid(in),in.readUTF(),in.readLong());
            if(in.available()!=0 || !message.valid(now)) throw new IllegalArgumentException("Departure expired");
            return message;
        } catch(IOException error) { throw new IllegalArgumentException("Departure payload",error); }
    }
    private static void uuid(DataOutputStream out,UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static byte[] mac(byte[] body,String secret) {
        if(secret==null || secret.length()<32) throw new IllegalArgumentException("Departure signing key unavailable");
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return mac.doFinal(body);
        } catch(GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
}
