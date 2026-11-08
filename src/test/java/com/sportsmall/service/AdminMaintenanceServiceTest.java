package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class AdminMaintenanceServiceTest {
    private static final AuthenticatedUser SESSION=new AuthenticatedUser(12,"admin","ADMIN");
    private static Function<AuthenticatedUser,UserAccount> account(String role) {
        return c->new UserAccount(12,"admin","a@example.test",null,role,1);
    }
    @Test void accountRoleIsRecheckedBeforeAnyAdminOperation() {
        var users=new AdminUserMutationService(account("USER"),(a,b,c,d)->fail());
        var orders=new AdminOrderService(account("USER"),(a,b)->fail(),(a,b,c)->fail());
        assertEquals("ADMIN_REQUIRED",assertThrows(MallBusinessException.class,()->users.update(SESSION,1,"USER",1)).code());
        assertThrows(MallBusinessException.class,()->orders.find(SESSION,null,OrderFilter.all()));
        assertThrows(MallBusinessException.class,()->orders.changeStatus(SESSION,1,1));
    }
    @Test void roleAndAccountStateCannotAcceptArbitraryValues() {
        var service=new AdminUserMutationService(account("ADMIN"),(a,b,c,d)->fail());
        assertThrows(MallBusinessException.class,()->service.update(SESSION,0,"USER",1));
        for(String role:Arrays.asList(null,"","ROOT","admin"))assertThrows(MallBusinessException.class,()->service.update(SESSION,1,role,1));
        for(int status:List.of(-1,2))assertThrows(MallBusinessException.class,()->service.update(SESSION,1,"USER",status));
    }
    @Test void actorPassedToTransactionalDaoComesFromCurrentAccount() {
        var service=new AdminUserMutationService(account("ADMIN"),(actor,target,role,status)->{
            assertEquals(12,actor);assertEquals(9,target);return new AdminUserSummary(target,"target",role,status);
        });
        assertEquals(0,service.update(SESSION,9,"USER",0).status());
    }
    @Test void orderProcessingRejectsInvalidIdsAndNonTerminalTargets() {
        var service=new AdminOrderService(account("ADMIN"),(a,b)->List.of(),(a,b,c)->fail());
        assertThrows(MallBusinessException.class,()->service.find(SESSION,0L,OrderFilter.all()));
        assertThrows(MallBusinessException.class,()->service.changeStatus(SESSION,0,1));
        for(int status:List.of(-1,0,3))assertThrows(MallBusinessException.class,()->service.changeStatus(SESSION,1,status));
        assertTrue(service.find(SESSION,null,OrderFilter.all()).isEmpty());
    }
    @Test void orderWriterReceivesActorAndImmutableRequestFieldsOnly() {
        var service=new AdminOrderService(account("ADMIN"),(a,b)->List.of(),(actor,id,status)->{
            assertEquals(12,actor);assertEquals(99,id);assertEquals(2,status);return null;
        });
        assertNull(service.changeStatus(SESSION,99,2));
    }
}
