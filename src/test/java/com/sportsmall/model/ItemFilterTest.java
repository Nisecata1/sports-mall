package com.sportsmall.model;

import com.sportsmall.exception.MallBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemFilterTest {
    @Test void defaultsAndWhitespaceHaveClearMeaning() {
        assertEquals(new ItemFilter(" \n",null,null,null,"",1,10),ItemFilter.defaults());
        var filter=new ItemFilter(" 篮球 ",2L,new BigDecimal("0"),new BigDecimal("100"),"price_asc",2,5);
        assertEquals("篮球",filter.keyword());assertEquals(new BigDecimal("0.00"),filter.minPrice());assertEquals(5L,filter.offset());
        assertEquals(1,filter.withPage(1).page());assertEquals(filter.keyword(),filter.withPage(1).keyword());
    }
    @Test void literalWildcardCharactersAreEscaped() {
        assertEquals("%20!%!!!_%",new ItemFilter("20%!_",null,null,null,"id",1,10).keywordPattern());
        assertNull(ItemFilter.defaults().keywordPattern());
    }
    @Test void keywordCountsUnicodeCharactersAndHasBound() {
        assertEquals(200,new ItemFilter("𠮷".repeat(200),null,null,null,null,1,10).keyword().codePointCount(0,400));
        invalid(() -> new ItemFilter("字".repeat(201),null,null,null,null,1,10));
    }
    @ParameterizedTest @ValueSource(strings={"-1","0.001","100000000.00"})
    void invalidPricesRejectedWithoutRounding(String price) {
        invalid(() -> new ItemFilter(null,null,new BigDecimal(price),null,"id",1,10));
        invalid(() -> new ItemFilter(null,null,null,new BigDecimal(price),"id",1,10));
    }
    @Test void reversedPriceBoundsRejectedAndEqualBoundsAllowed() {
        invalid(() -> new ItemFilter(null,null,BigDecimal.TEN,BigDecimal.ONE,"id",1,10));
        assertEquals(new BigDecimal("10.00"),new ItemFilter(null,null,BigDecimal.TEN,BigDecimal.TEN,"id",1,10).maxPrice());
    }
    @ParameterizedTest @ValueSource(strings={"price","PRICE_ASC","price desc; DROP TABLE items","random"})
    void sortIsAWhitelistRatherThanSql(String sort) {
        invalid(() -> new ItemFilter(null,null,null,null,sort,1,10));
    }
    @Test void idAndPaginationBoundsValidatedAndOffsetDoesNotOverflowInt() {
        invalid(() -> new ItemFilter(null,0L,null,null,"id",1,10));
        invalid(() -> new ItemFilter(null,-1L,null,null,"id",1,10));
        invalid(() -> ItemFilter.defaults().withPage(0));invalid(() -> ItemFilter.defaults().withPage(-1));
        invalid(() -> new ItemFilter(null,null,null,null,"id",1,0));
        invalid(() -> new ItemFilter(null,null,null,null,"id",1,101));
        assertEquals(214748364600L,new ItemFilter(null,null,null,null,"id",Integer.MAX_VALUE,100).offset());
    }
    @Test void pageMetadataHandlesEmptyAndPartialPagesWithoutInventingResults() {
        var item=new ItemView(1,"球",BigDecimal.TEN,1,"分类");
        var list=new ArrayList<>(List.of(item));var page=new ItemPage(list,3,1,2);list.clear();
        assertEquals(1,page.items().size());assertEquals(2,page.totalPages());assertFalse(page.hasPrevious());assertTrue(page.hasNext());
        var last=new ItemPage(List.of(item),3,2,2);assertFalse(last.hasNext());assertTrue(last.hasPrevious());
        var empty=new ItemPage(List.of(),0,1,2);assertEquals(0,empty.totalPages());assertFalse(empty.hasNext());
        var beyond=new ItemPage(List.of(),3,20,2);assertEquals(20,beyond.page());assertFalse(beyond.hasNext());
        assertThrows(UnsupportedOperationException.class,() -> page.items().clear());
    }
    private static void invalid(Runnable action) { assertEquals("INVALID_ITEM_FILTER",assertThrows(MallBusinessException.class,action::run).code()); }
}
