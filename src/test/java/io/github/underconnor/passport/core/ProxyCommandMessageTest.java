package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProxyCommandMessageTest {
    private static final String KEY="synthetic-proxy-command-key-for-tests-only";
    private static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
    private static final UUID REQUEST=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR=UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    // Identical in both repositories; generated independently with big-endian fields and HMAC-SHA256.
    private static final String GOLDEN="ABNwYXNzcG9ydC5jb21tYW5kLnYxAAAAAAAAAAAAAAAAAAAAAQARIjNEVWZ3iJmqu8zd7v8ACXNzdV9sb2JieQAAAaEENf8QAB1wYXNzcG9ydCBzZXJ2ZXIg7Y+J7ZmUIOyVvOyDnc2R6phrDMBvCDT5H7mczmdFGPHvIEnFMagnjeW2xWh3";

    private ProxyCommandMessage request() { return ProxyCommandMessage.request(ACTOR,"ssu_lobby","passport server 평화 야생",NOW); }
    private ProxyCommandMessage fixed() { return new ProxyCommandMessage(REQUEST,ACTOR,"ssu_lobby",NOW.plusSeconds(10).toEpochMilli(),"passport server 평화 야생"); }

    @Test void identicalGoldenFixtureProtectsPaperAndVelocityWireCompatibility() {
        byte[] bytes=Base64.getDecoder().decode(GOLDEN);
        assertArrayEquals(bytes,fixed().encode(KEY));
        assertEquals(fixed(),ProxyCommandMessage.decode(bytes,KEY,NOW));
        assertEquals("passport:command",ProxyCommandMessage.CHANNEL);
    }

    @Test void requestRoundTripsAndExpiresAfterTenSeconds() {
        var message=request();
        assertEquals(ACTOR,message.actor()); assertEquals("ssu_lobby",message.serverId());
        assertEquals(NOW.plusSeconds(10).toEpochMilli(),message.expiresAt());
        assertEquals(message,ProxyCommandMessage.decode(message.encode(KEY),KEY,NOW.plusMillis(9999)));
        assertTrue(message.valid(NOW)); assertFalse(message.valid(NOW.plusSeconds(10)));
        assertFalse(message.valid(NOW.minusMillis(1)));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(message.encode(KEY),KEY,NOW.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(message.encode(KEY),KEY,NOW.minusMillis(1)));
    }

    @Test void rootAliasesNormalizeToOnePublicPassportCommand() {
        for(String root:List.of("passport","passport:passport","PASSPORT","Passport:Passport")) {
            assertEquals("passport link",ProxyCommandMessage.normalize(root,new String[0]));
            assertEquals("passport help",ProxyCommandMessage.normalize(root,new String[]{"HELP"}));
        }
        for(String root:List.of("서버","passport:서버")) {
            assertEquals("passport server",ProxyCommandMessage.normalize(root,new String[0]));
            assertEquals("passport server 평화 야생",ProxyCommandMessage.normalize(root,new String[]{"평화","야생"}));
        }
    }

    @Test void allPublicActionsAndOptionalArgumentsAreAccepted() {
        for(String command:List.of("help","link","web","status","list","list 2","server","server ssu_survival_2609","queue","queue leave")) {
            String normalized=ProxyCommandMessage.normalize("passport",command.split(" "));
            assertEquals("passport "+command,normalized);
            var message=ProxyCommandMessage.request(ACTOR,"ssu_lobby",normalized,NOW);
            assertEquals(message,ProxyCommandMessage.decode(message.encode(KEY),KEY,NOW));
        }
        assertEquals("passport queue leave",ProxyCommandMessage.normalize("passport",new String[]{"QUEUE","LEAVE"}));
        assertEquals("passport server 평화 야생",ProxyCommandMessage.normalize("passport",new String[]{" server ","평화  야생 "}));
    }

    @Test void serverNamesHaveABoundedSpaceSupportingCharacterSet() {
        assertEquals("passport server "+"가".repeat(80),ProxyCommandMessage.normalize("서버",new String[]{"가".repeat(80)}));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("서버",new String[]{"가".repeat(81)}));
        for(String name:List.of("foo;op","foo|op","/lp","minecraft:op","<player>","foo\\bar"))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("서버",new String[]{name}),name);
    }

    @Test void arbitraryRootsAndAdministrativeCommandsAreNeverRelayed() {
        for(String root:List.of("op","lp","minecraft:passport","other:passport","/passport","//passport","passport ","passport\n"))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize(root,new String[]{"help"}),root);
        for(String command:List.of("adminweb","player UBConnor","tp UBConnor","announce hello","op somebody","confirm","console passport help"))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("passport",command.split(" ")),command);
    }

    @Test void extraArgumentsAndInvalidPageOrQueueActionsAreRejected() {
        for(String command:List.of("help admin","link somebody","web somebody","status somebody","list 0","list -1","list +1","list 01","list 1 2","list 1.0","queue join","queue leave someone"))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("passport",command.split(" ")),command);
    }

    @Test void controlsNewlinesAndInvisibleSeparatorsAreRejected() {
        for(String separator:List.of("\n","\r","\t","\u0000","\u001b","\u007f","\u0085","\u200b","\u2028","\u2029","\uD800")) {
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("passport",new String[]{"server","lobby"+separator+"op"}));
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("passport",new String[]{"help"+separator}));
        }
    }

    @Test void recordRejectsAnythingThatIsNotAlreadyCanonical() {
        for(String command:List.of("passport","passport:passport help","서버 lobby","PASSPORT help","passport HELP","passport  help","passport help "," passport help","passport queue LEAVE","passport adminweb"))
            assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(REQUEST,ACTOR,"ssu_lobby",fixed().expiresAt(),command),command);
        assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(REQUEST,ACTOR,"ssu_lobby",fixed().expiresAt(),"passport list "+"1".repeat(257)));
    }

    @Test void nullsBadServerIdsAndNonpositiveExpiryAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(null,ACTOR,"ssu_lobby",fixed().expiresAt(),"passport help"));
        assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(REQUEST,null,"ssu_lobby",fixed().expiresAt(),"passport help"));
        assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(REQUEST,ACTOR,"ssu_lobby",fixed().expiresAt(),null));
        for(String server:Arrays.asList(null,"","Lobby","../lobby","lobby foo","1lobby","a".repeat(65)))
            assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(REQUEST,ACTOR,server,fixed().expiresAt(),"passport help"));
        for(long expiry:new long[]{0,-1,Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class,() -> new ProxyCommandMessage(REQUEST,ACTOR,"ssu_lobby",expiry,"passport help"));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize(null,new String[0]));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("passport",null));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.normalize("passport",new String[]{null}));
    }

    @Test void forgedSignaturesWrongKeysAndMissingKeysAreRejected() {
        byte[] bytes=fixed().encode(KEY), tampered=bytes.clone(); tampered[40]^=1;
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(tampered,KEY,NOW));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(bytes,KEY+"different",NOW));
        for(String key:Arrays.asList(null,"","short")) {
            assertThrows(IllegalArgumentException.class,() -> fixed().encode(key));
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(bytes,key,NOW));
        }
    }

    @Test void sizeAndTruncatedBodiesAreRejectedEvenWithAValidSignature() throws Exception {
        for(byte[] bytes:Arrays.asList(null,new byte[0],new byte[32],new byte[1025],Arrays.copyOf(fixed().encode(KEY),50)))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(bytes,KEY,NOW));
        byte[] bytes=fixed().encode(KEY), body=Arrays.copyOf(bytes,bytes.length-32);
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(sign(Arrays.copyOf(body,body.length-1)),KEY,NOW));
    }

    @Test void validMacDoesNotPermitWrongDomainTrailingBytesOrForbiddenCommands() throws Exception {
        for(String domain:List.of("passport.teleport.v1","passport.command.v2",""))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(raw(domain,"passport help",false),KEY,NOW));
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(raw("passport.command.v1","passport help",true),KEY,NOW));
        for(String command:List.of("passport adminweb","lp user x permission set * true","passport\nhelp","passport  help","passport server "+"a".repeat(81)))
            assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(raw("passport.command.v1",command,false),KEY,NOW));
    }

    @Test void teleportDomainCannotBeReusedAsACommandRequest() {
        var teleport=TeleportMessage.request(ACTOR,UUID.randomUUID(),"ssu_lobby",NOW);
        assertThrows(IllegalArgumentException.class,() -> ProxyCommandMessage.decode(teleport.encode(KEY),KEY,NOW));
        assertThrows(IllegalArgumentException.class,() -> TeleportMessage.decode(fixed().encode(KEY),KEY,NOW));
    }

    private byte[] raw(String domain,String command,boolean trailing) throws Exception {
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)) {
            out.writeUTF(domain);
            for(UUID id:List.of(REQUEST,ACTOR)) { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
            out.writeUTF("ssu_lobby"); out.writeLong(fixed().expiresAt()); out.writeUTF(command);
            if(trailing) out.writeByte(0);
        }
        return sign(buffer.toByteArray());
    }
    private byte[] sign(byte[] body) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        byte[] signature=mac.doFinal(body), result=Arrays.copyOf(body,body.length+signature.length);
        System.arraycopy(signature,0,result,body.length,signature.length); return result;
    }
}
