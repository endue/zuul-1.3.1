package com.netflix.zuul.filters;


import com.netflix.zuul.ZuulFilter;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mhawthorne
 * 用于管理加载的filter，数据结构比较简单，使用
 * ConcurrentHashMap<String, ZuulFilter> filters，启动key为filter的name：file.getAbsolutePath() + file.getName();
 */
public class FilterRegistry {

    private static final FilterRegistry INSTANCE = new FilterRegistry();

    public static final FilterRegistry instance() {
        return INSTANCE;
    }
    // 保存ZuulFilter的名称和ZuulFilter的关系
    private final ConcurrentHashMap<String, ZuulFilter> filters = new ConcurrentHashMap<String, ZuulFilter>();

    private FilterRegistry() {
    }

    public ZuulFilter remove(String key) {
        return this.filters.remove(key);
    }

    public ZuulFilter get(String key) {
        return this.filters.get(key);
    }

    public void put(String key, ZuulFilter filter) {
        this.filters.putIfAbsent(key, filter);
    }

    public int size() {
        return this.filters.size();
    }

    public Collection<ZuulFilter> getAllFilters() {
        return this.filters.values();
    }

}
