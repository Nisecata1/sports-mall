package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommentManagementServiceTest {
    private static final String ID="1234567890abcdef12345678";
    private static final AuthenticatedUser SESSION=new AuthenticatedUser(7,"user","ADMIN");
    private static final CommentView COMMENT=new CommentView(ID,7,8,"原评论",5,List.of(),Instant.EPOCH);
    private CommentManagementService service(String role) {
        return new CommentManagementService(current->new UserAccount(7,"user","a@example.test",null,role,1),
                owner->{assertEquals(7,owner);return List.of(COMMENT);},()->List.of(COMMENT),
                (owner,id,text,rating,tags)->new CommentView(id,owner,8,text,rating,tags,Instant.EPOCH),
                (id,owner)->true);
    }
    @Test void currentDatabaseRoleOverridesClaimedAdminRole() {
        var service=service("USER");
        assertEquals(List.of(COMMENT),service.mine(SESSION));
        assertEquals("ADMIN_REQUIRED",assertThrows(MallBusinessException.class,()->service.all(SESSION)).code());
        assertEquals("ADMIN_REQUIRED",assertThrows(MallBusinessException.class,()->service.deleteAsAdmin(SESSION,ID)).code());
    }
    @Test void editNormalizesTextAndTagsWithoutChangingReferences() {
        var changed=service("USER").updateMine(SESSION,ID,"  修改  ",3,"训练,训练，篮球");
        assertEquals("修改",changed.content());assertEquals(List.of("训练","篮球"),changed.tags());
        assertEquals(7,changed.userId());assertEquals(8,changed.itemId());assertEquals(Instant.EPOCH,changed.createdAt());
    }
    @Test void deleteScopeIsOwnedForOrdinaryAndExplicitlyUnscopedForAdmin() {
        List<Long> owners=new ArrayList<>();
        var service=new CommentManagementService(c->new UserAccount(7,"user","a@example.test",null,"ADMIN",1),
                id->List.of(),List::of,(a,b,c,d,e)->COMMENT,(id,owner)->{owners.add(owner);return false;});
        assertFalse(service.deleteMine(SESSION,ID));assertFalse(service.deleteAsAdmin(SESSION,ID));
        assertEquals(Arrays.asList(7L,null),owners);
    }
    @Test void malformedIdsRejectedBeforeMutation() {
        for(String id:Arrays.asList(null,"","$where","a".repeat(25))) {
            assertThrows(MallBusinessException.class,()->service("USER").deleteMine(SESSION,id));
            assertThrows(MallBusinessException.class,()->service("USER").updateMine(SESSION,id,"合法",5,""));
        }
    }
    @Test void contentRatingAndTagBoundariesRejected() {
        var service=service("USER");
        for(String text:List.of(" ","字".repeat(2001)))
            assertThrows(MallBusinessException.class,()->service.updateMine(SESSION,ID,text,5,""));
        for(int score:List.of(0,6))assertThrows(MallBusinessException.class,()->service.updateMine(SESSION,ID,"合法",score,""));
        for(String tags:List.of("a,b,c,d,e,f","长".repeat(21)))
            assertThrows(MallBusinessException.class,()->service.updateMine(SESSION,ID,"合法",5,tags));
        assertEquals(2000,service.updateMine(SESSION,ID,"字".repeat(2000),1,"").content().length());
    }
    @Test void disabledAccountStopsBeforeReadingOrWritingMongo() {
        var service=new CommentManagementService(c->{throw new MallBusinessException("ACCOUNT_DISABLED","停用");},
                id->fail(),()->fail(),(a,b,c,d,e)->fail(),(id,owner)->fail());
        assertThrows(MallBusinessException.class,()->service.mine(SESSION));
        assertThrows(MallBusinessException.class,()->service.updateMine(SESSION,ID,"合法",5,""));
        assertThrows(MallBusinessException.class,()->service.deleteMine(SESSION,ID));
    }
}
