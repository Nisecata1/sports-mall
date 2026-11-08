package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ItemSearchServiceTest {
    private static final AuthenticatedUser USER=new AuthenticatedUser(1,"user","USER");
    private static final UserAccount ACCOUNT=new UserAccount(1,"user","u@example.test",null,"USER",1);
    @Test void ordinaryUserCanQueryAndReadCategoriesWithoutAdminPermission() {
        var filter=ItemFilter.defaults();var expected=new ItemPage(List.of(),0,1,10);
        var categories=List.of(new CategoryView(1,"分类",null));
        var service=new ItemSearchService(user -> { assertSame(USER,user);return ACCOUNT; },query -> {
            assertSame(filter,query);return expected;
        },() -> categories);
        assertSame(expected,service.searchFor(USER,filter));assertEquals(categories,service.categoriesFor(USER));
    }
    @Test void invalidOrDisabledAccountDoesNotReachCatalogData() {
        var service=new ItemSearchService(user -> { throw new MallBusinessException("ACCOUNT_DISABLED","停用"); },
                query -> fail("No query"),() -> fail("No categories"));
        assertEquals("ACCOUNT_DISABLED",assertThrows(MallBusinessException.class,() -> service.searchFor(USER,ItemFilter.defaults())).code());
        assertEquals("ACCOUNT_DISABLED",assertThrows(MallBusinessException.class,() -> service.categoriesFor(USER)).code());
    }
}
