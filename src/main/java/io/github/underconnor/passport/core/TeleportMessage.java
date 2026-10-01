package io.github.underconnor.passport.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Authenticated, short-lived proxy/backend messages. A channel name alone is not authorization. */
public record TeleportMessage(String kind, UUID requestId, UUID actor, UUID target, String serverId,
                              long expiresAt, String result) {
    public static final String CHANNEL="passport:teleport";
    private static final String DOMAIN="passport.teleport.v1";
    private static final Set<String> RESULTS=Set.of("ok","denied","unavailable","failed");
    public TeleportMessage {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actor); Objects.requireNonNull(target);
        if(!serverId.matches("[a-z][a-z0-9_-]{0,63}") || expiresAt<=0
            || !(kind.equals("REQUEST") && result.isEmpty() || kind.equals("RESULT") && RESULTS.contains(result)))
            throw new IllegalArgumentException("Invalid teleport message");
    }
    public static TeleportMessage request(UUID actor,UUID target,String serverId,Instant now) {
        return new TeleportMessage("REQUEST",UUID.randomUUID(),actor,target,serverId,now.plusSeconds(15).toEpochMilli(),"");
    }
    public TeleportMessage reply(String result) { return new TeleportMessage("RESULT",requestId,actor,target,serverId,expiresAt,result); }
    public boolean valid(Instant now) { long time=now.toEpochMilli(); return expiresAt>time && expiresAt<=time+15_000; }
    private byte[] payload() throws IOException {
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)) {
            out.writeUTF(DOMAIN); out.writeUTF(kind); uuid(out,requestId); uuid(out,actor); uuid(out,target);
            out.writeUTF(serverId); out.writeLong(expiresAt); out.writeUTF(result);
        }
        return buffer.toByteArray();
    }
    private static void uuid(DataOutputStream out,UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static byte[] mac(byte[] data,String secret) {
        if(secret==null || secret.length()<32) throw new IllegalArgumentException("Teleport signing key unavailable");
        try {
            Mac hmac=Mac.getInstance("HmacSHA256"); hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return hmac.doFinal(data);
        } catch(GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    public byte[] encode(String secret) {
        try { byte[] body=payload(), signature=mac(body,secret); byte[] message=Arrays.copyOf(body,body.length+signature.length);
            System.arraycopy(signature,0,message,body.length,signature.length); return message;
        } catch(IOException error) { throw new IllegalStateException(error); }
    }
    public static TeleportMessage decode(byte[] message,String secret,Instant now) {
        if(message==null || message.length<100 || message.length>512) throw new IllegalArgumentException("Teleport size");
        byte[] body=Arrays.copyOf(message,message.length-32), signature=Arrays.copyOfRange(message,message.length-32,message.length);
        if(!MessageDigest.isEqual(mac(body,secret),signature)) throw new IllegalArgumentException("Teleport signature");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
            if(!DOMAIN.equals(in.readUTF())) throw new IllegalArgumentException("Teleport version");
            var value=new TeleportMessage(in.readUTF(),uuid(in),uuid(in),uuid(in),in.readUTF(),in.readLong(),in.readUTF());
            if(in.available()!=0 || !value.valid(now)) throw new IllegalArgumentException("Teleport expired");
            return value;
        } catch(IOException error) { throw new IllegalArgumentException("Teleport payload",error); }
    }
}
