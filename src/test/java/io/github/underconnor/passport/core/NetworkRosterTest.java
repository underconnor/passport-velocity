package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRosterTest {
    final Instant now=Instant.now();
    final Policy viewer=new Policy(UUID.randomUUID(),"active",Set.of("build"),"","",1,now,now.plusSeconds(60),false,null,false,Map.of("build","건축 서버"));
    @Test void paginationCountsTheWholeSnapshotAndSortsNamesBeforeSlicing() {
        var entries=IntStream.range(0,1000).mapToObj(i -> new NetworkRoster.Entry(String.format("Player%04d",999-i),"build","건축 서버")).toList();
        var page=NetworkRoster.page(entries,2); assertEquals(1000,page.total()); assertEquals(50,page.pages()); assertEquals(20,page.entries().size());
        assertEquals("Player0020",page.entries().getFirst().ign()); assertEquals("Player0039",page.entries().getLast().ign());
    }
    @Test void invalidPagesAndArgumentsAreRejectedWithoutOverflowAndEmptyNetworkStillHasOnePage() {
        assertEquals(1,NetworkRoster.pageNumber(new String[]{"list"})); assertEquals(3,NetworkRoster.pageNumber(new String[]{"list","3"}));
        for(String value:List.of("0","-1","1.5","NaN","999999999999")) assertThrows(IllegalArgumentException.class,() -> NetworkRoster.pageNumber(new String[]{"list",value}));
        assertThrows(IllegalArgumentException.class,() -> NetworkRoster.pageNumber(new String[]{"list","1","extra"}));
        assertEquals(0,NetworkRoster.page(List.of(),1).total()); assertEquals(1,NetworkRoster.page(List.of(),1).pages());
        assertThrows(IllegalArgumentException.class,() -> NetworkRoster.page(List.of(),2));
    }
    @Test void ordinaryUsersOnlySeeAllowedDisplayLabelsButStillSeeEveryPlayersIgn() {
        var allowed=new NetworkRoster.Entry("Builder","build","old label"); var hidden=new NetworkRoster.Entry("Other","staff_secret","운영 서버");
        assertEquals("건축 서버",NetworkRoster.location(allowed,viewer,false,"limbo",now));
        assertEquals("비공개 서버",NetworkRoster.location(hidden,viewer,false,"limbo",now));
        assertEquals("비공개 서버",NetworkRoster.location(allowed,viewer,false,"limbo",now.plusSeconds(60)));
        assertEquals("Other",NetworkRoster.page(List.of(hidden),1).entries().getFirst().ign());
    }
    @Test void adminAndConsoleShowAllAvailableLabelsButNeverStorageIds() {
        assertEquals("운영 서버",NetworkRoster.location(new NetworkRoster.Entry("Other","staff_secret","운영 서버"),null,true,"limbo",now));
        assertEquals("서버 정보 확인 중",NetworkRoster.location(new NetworkRoster.Entry("Other","staff_secret",null),null,true,"limbo",now));
        assertEquals("인증 대기실",NetworkRoster.location(new NetworkRoster.Entry("New","limbo",null),null,true,"limbo",now));
        assertEquals("연결 중",NetworkRoster.location(new NetworkRoster.Entry("New",null,null),null,true,"limbo",now));
    }
}
