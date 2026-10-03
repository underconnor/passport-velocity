package io.github.underconnor.passport.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Signed, player-bound requests for the public Passport commands only. */
public record ProxyCommandMessage(UUID requestId, UUID actor, String serverId, long expiresAt, String command) {
    public static final String CHANNEL="passport:command";
    private static final String DOMAIN="passport.command.v1";
    private static final int MAX_MESSAGE_BYTES=1024;
    private static final int SIGNATURE_BYTES=32;
    private static final long TTL_MILLIS=10_000;
    private static final Set<String> NO_ARGUMENTS=Set.of("help","link","web","status");

    public ProxyCommandMessage {
        if(requestId==null || actor==null || serverId==null || !serverId.matches("[a-z][a-z0-9_-]{0,63}")
            || expiresAt<=0 || command==null || command.length()>256) throw invalid();
        String[] parts=command.split(" ",-1);
        if(!command.equals(normalize(parts[0],Arrays.copyOfRange(parts,1,parts.length)))) throw invalid();
    }

    public static ProxyCommandMessage request(UUID actor,String serverId,String command,Instant now) {
        return new ProxyCommandMessage(UUID.randomUUID(),actor,serverId,now.plusMillis(TTL_MILLIS).toEpochMilli(),command);
    }

    /** The root is supplied by Bukkit separately; arbitrary commands and administrator actions are rejected. */
    public static String normalize(String label,String[] args) {
        if(label==null || args==null) throw invalid();
        boolean serverAlias=switch(label.toLowerCase(Locale.ROOT)) {
            case "passport", "passport:passport" -> false;
            case "서버", "passport:서버" -> true;
            default -> throw invalid();
        };
        var words=new ArrayList<String>();
        if(serverAlias) words.add("server");
        int inputLength=0;
        for(String arg:args) {
            if(arg==null || arg.length()>256 || unsafe(arg)) throw invalid();
            inputLength+=arg.length()+1;
            if(inputLength>256) throw invalid();
            for(String word:arg.split(" +")) if(!word.isEmpty()) words.add(word);
        }
        if(words.isEmpty()) words.add("link");
        String action=words.getFirst().toLowerCase(Locale.ROOT);
        String suffix="";
        if(NO_ARGUMENTS.contains(action)) {
            if(words.size()!=1) throw invalid();
        } else switch(action) {
            case "list" -> {
                if(words.size()>2 || words.size()==2 && !words.get(1).matches("[1-9][0-9]*")) throw invalid();
                if(words.size()==2) suffix=" "+words.get(1);
            }
            case "server" -> {
                if(words.size()>1) {
                    String server=Normalizer.normalize(String.join(" ",words.subList(1,words.size())),Normalizer.Form.NFC);
                    if(!server.matches("[\\p{L}\\p{N}_ -]{1,80}")) throw invalid();
                    suffix=" "+server;
                }
            }
            case "queue" -> {
                if(words.size()>2 || words.size()==2 && !words.get(1).equalsIgnoreCase("leave")) throw invalid();
                if(words.size()==2) suffix=" leave";
            }
            default -> throw invalid();
        }
        String result="passport "+action+suffix;
        if(result.length()>256) throw invalid();
        return result;
    }

    private static boolean unsafe(String value) {
        return value.codePoints().anyMatch(point -> Character.isISOControl(point)
            || Character.getType(point)==Character.FORMAT || Character.getType(point)==Character.SURROGATE
            || Character.getType(point)==Character.LINE_SEPARATOR || Character.getType(point)==Character.PARAGRAPH_SEPARATOR
            || Character.isWhitespace(point) && point!=' ');
    }

    public boolean valid(Instant now) {
        long time=now.toEpochMilli();
        return expiresAt>time && expiresAt-time>0 && expiresAt-time<=TTL_MILLIS;
    }

    private byte[] payload() throws IOException {
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)) {
            out.writeUTF(DOMAIN); uuid(out,requestId); uuid(out,actor);
            out.writeUTF(serverId); out.writeLong(expiresAt); out.writeUTF(command);
        }
        return buffer.toByteArray();
    }

    private static void uuid(DataOutputStream out,UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static byte[] mac(byte[] data,String secret) {
        if(secret==null || secret.length()<32) throw new IllegalArgumentException("Proxy command signing key unavailable");
        try {
            Mac hmac=Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return hmac.doFinal(data);
        } catch(GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    public byte[] encode(String secret) {
        try {
            byte[] body=payload();
            if(body.length+SIGNATURE_BYTES>MAX_MESSAGE_BYTES) throw invalid();
            byte[] signature=mac(body,secret), message=Arrays.copyOf(body,body.length+signature.length);
            System.arraycopy(signature,0,message,body.length,signature.length);
            return message;
        } catch(IOException error) { throw new IllegalStateException(error); }
    }

    public static ProxyCommandMessage decode(byte[] message,String secret,Instant now) {
        if(message==null || message.length<=SIGNATURE_BYTES || message.length>MAX_MESSAGE_BYTES) throw invalid();
        byte[] body=Arrays.copyOf(message,message.length-SIGNATURE_BYTES);
        byte[] signature=Arrays.copyOfRange(message,message.length-SIGNATURE_BYTES,message.length);
        if(!MessageDigest.isEqual(mac(body,secret),signature)) throw new IllegalArgumentException("Proxy command signature");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
            if(!DOMAIN.equals(in.readUTF())) throw invalid();
            var result=new ProxyCommandMessage(uuid(in),uuid(in),in.readUTF(),in.readLong(),in.readUTF());
            if(in.available()!=0 || !result.valid(now)) throw invalid();
            return result;
        } catch(IOException error) { throw new IllegalArgumentException("Proxy command payload",error); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid proxy command"); }
}
