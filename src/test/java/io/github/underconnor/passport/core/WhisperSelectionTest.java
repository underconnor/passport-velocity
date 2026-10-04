package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WhisperSelectionTest {
    @Test void ignHasPriorityAndRealNamesNeverPickOneAmbiguousPlayer() {
        var first=new WhisperSelection.Entry(UUID.randomUUID(),"Connor","정지원");
        var second=new WhisperSelection.Entry(UUID.randomUUID(),"Other","정지원");
        var sameAsIgn=new WhisperSelection.Entry(UUID.randomUUID(),"Third","Connor");
        var entries=List.of(second,first,sameAsIgn);
        assertEquals(List.of(first),WhisperSelection.targets(entries,"cOnNoR"));
        assertEquals(List.of(first,second),WhisperSelection.targets(entries,"정지원"));
        assertTrue(WhisperSelection.targets(entries,"missing").isEmpty());
    }
    @Test void unicodeNamesNormalizeButIncompleteAndInvalidIdentifiersNeverMatch() {
        var player=new WhisperSelection.Entry(UUID.randomUUID(),"Connor","정지원");
        assertEquals(List.of(player),WhisperSelection.targets(List.of(player),java.text.Normalizer.normalize("정지원",java.text.Normalizer.Form.NFD)));
        for(String query:List.of("정","정지원 ","정\n지원"," ","a".repeat(41))) assertTrue(WhisperSelection.targets(List.of(player),query).isEmpty());
    }
    @Test void completionIncludesIgnAndVerifiedRealNamesWithoutSenderAndKeepsLimit() {
        UUID sender=UUID.randomUUID(); var entries=new ArrayList<WhisperSelection.Entry>();
        entries.add(new WhisperSelection.Entry(sender,"Self","본인"));
        entries.add(new WhisperSelection.Entry(UUID.randomUUID(),"Target","정지원"));
        entries.add(new WhisperSelection.Entry(UUID.randomUUID(),"Unverified",""));
        assertEquals(List.of("정지원"),WhisperSelection.suggestions(entries,sender,"정"));
        assertFalse(WhisperSelection.suggestions(entries,sender,"").contains("Self"));
        assertTrue(WhisperSelection.suggestions(entries,sender,"").contains("Unverified"));
        for(int index=0;index<100;index++) entries.add(new WhisperSelection.Entry(UUID.randomUUID(),"Player"+index,""));
        assertEquals(50,WhisperSelection.suggestions(entries,sender,"Player").size());
    }
    @Test void messageIsBoundedPlainUnicodeTextAndRecognizesOnlyRequestedAliases() {
        assertTrue(WhisperSelection.safeMessage("<red>§chello https://example.test"));
        assertTrue(WhisperSelection.safeMessage("😀".repeat(300)));
        for(String message:List.of(""," ","hello\nworld","x\u0000","😀".repeat(301))) assertFalse(WhisperSelection.safeMessage(message));
        for(String root:List.of("msg Target hello","tell Target hi","w Target hi","귓 정지원 안녕")) assertTrue(WhisperSelection.owns(root));
        for(String root:List.of("reply x","msgall x","m x","minecraft:msg x")) assertFalse(WhisperSelection.owns(root));
    }
}
