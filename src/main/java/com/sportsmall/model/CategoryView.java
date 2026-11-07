package com.sportsmall.model;

/** parentId为空表示根分类；非空时引用另一条分类记录的categoryId。 */
public record CategoryView(long categoryId, String name, Long parentId) {
}
