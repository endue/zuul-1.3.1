/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul;

import com.netflix.zuul.filters.FilterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This class is one of the core classes in Zuul. It compiles, loads from a File, and checks if source code changed.
 * It also holds ZuulFilters by filterType.
 *
 * @author Mikey Cohen
 *         Date: 11/3/11
 *         Time: 1:59 PM
 */
public class FilterLoader {
    final static FilterLoader INSTANCE = new FilterLoader();

    private static final Logger LOG = LoggerFactory.getLogger(FilterLoader.class);
    /**
     * 保存了文件完整路径名和文件上一次的变更时间戳,如：
     * key: D:\project\zuul-1.3.1\zuul-simple-webapp\src\main\groovy\filters\pre\DebugFilter.groovyDebugFilter.groovy
     * value: 1599533022013
     */
    private final ConcurrentHashMap<String, Long> filterClassLastModified = new ConcurrentHashMap<String, Long>();

    /**
     * 保存ZuulFilter的名称或对应的class
     * key：javaPreFilter
     * value：com.netflix.zuul.ZuulFilter
     */
    private final ConcurrentHashMap<String, String> filterClassCode = new ConcurrentHashMap<String, String>();

    /**
     * 保存ZuulFilter的名称
     * key：javaPreFilter
     * value：javaPreFilter
     */
    private final ConcurrentHashMap<String, String> filterCheck = new ConcurrentHashMap<String, String>();

    /**
     * 保存了ZuulFilter的类型以及对应类型的类列表
     * key: pre、post、route
     * value: List<ZuulFilter>
     */
    private final ConcurrentHashMap<String, List<ZuulFilter>> hashFiltersByType = new ConcurrentHashMap<String, List<ZuulFilter>>();

    /**
     * 里面有个ConcurrentHashMap<String, ZuulFilter> filters属性，保存了
     * key：ZuulFilter绝对路径+文件名
     * value：对应的ZuulFilter的关系
     */
    private FilterRegistry filterRegistry = FilterRegistry.instance();

    // 动态代码编译器，默认GroovyCompiler
    static DynamicCodeCompiler COMPILER;

    // 将ZuulFilter类的class文件生成对应实例，默认DefaultFilterFactory
    static FilterFactory FILTER_FACTORY = new DefaultFilterFactory();

    /**
     * Sets a Dynamic Code Compiler
     *
     * @param compiler
     */
    public void setCompiler(DynamicCodeCompiler compiler) {
        COMPILER = compiler;
    }

    // overidden by tests
    public void setFilterRegistry(FilterRegistry r) {
        this.filterRegistry = r;
    }

    /**
     * Sets a FilterFactory
     * 
     * @param factory
     */
    public void setFilterFactory(FilterFactory factory) {
        FILTER_FACTORY = factory;
    }
    
    /**
     * @return Singleton FilterLoader
     */
    public static FilterLoader getInstance() {
        return INSTANCE;
    }

    /**
     * Given source and name will compile and store the filter if it detects that the filter code has changed or
     * the filter doesn't exist. Otherwise it will return an instance of the requested ZuulFilter
     *
     * @param sCode source code
     * @param sName name of the filter
     * @return the ZuulFilter
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    public ZuulFilter getFilter(String sCode, String sName) throws Exception {

        if (filterCheck.get(sName) == null) {
            filterCheck.putIfAbsent(sName, sName);
            if (!sCode.equals(filterClassCode.get(sName))) {
                LOG.info("reloading code " + sName);
                filterRegistry.remove(sName);
            }
        }
        ZuulFilter filter = filterRegistry.get(sName);
        if (filter == null) {
            Class clazz = COMPILER.compile(sCode, sName);
            if (!Modifier.isAbstract(clazz.getModifiers())) {
                filter = (ZuulFilter) FILTER_FACTORY.newInstance(clazz);
            }
        }
        return filter;

    }

    /**
     * @return the total number of Zuul filters
     */
    public int filterInstanceMapSize() {
        return filterRegistry.size();
    }


    /**
     * From a file this will read the ZuulFilter source code, compile it, and add it to the list of current filters
     * a true response means that it was successful.
     *
     * @param file
     * @return true if the filter in file successfully read, compiled, verified and added to Zuul
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws IOException
     */
    public boolean putFilter(File file) throws Exception {
        // 完整路径：D:\project\zuul-1.3.1\zuul-simple-webapp\src\main\groovy\filters\pre\DebugFilter.groovyDebugFilter.groovy
        String sName = file.getAbsolutePath() + file.getName();
        // 文件已发生变更
        if (filterClassLastModified.get(sName) != null && (file.lastModified() != filterClassLastModified.get(sName))) {
            LOG.debug("reloading filter " + sName);
            // 删除filterRegistry中这个sName对应的ZuulFilter
            filterRegistry.remove(sName);
        }
        // 新建或重新编译
        ZuulFilter filter = filterRegistry.get(sName);
        if (filter == null) {
            Class clazz = COMPILER.compile(file);
            // 这里不能是抽象类
            if (!Modifier.isAbstract(clazz.getModifiers())) {
                filter = (ZuulFilter) FILTER_FACTORY.newInstance(clazz);
                List<ZuulFilter> list = hashFiltersByType.get(filter.filterType());
                if (list != null) {
                    hashFiltersByType.remove(filter.filterType()); //rebuild this list
                }
                filterRegistry.put(file.getAbsolutePath() + file.getName(), filter);
                filterClassLastModified.put(sName, file.lastModified());
                return true;
            }
        }

        return false;
    }

    /**
     * Returns a list of filters by the filterType specified
     *
     * @param filterType
     * @return a List<ZuulFilter>
     */
    public List<ZuulFilter> getFiltersByType(String filterType) {

        List<ZuulFilter> list = hashFiltersByType.get(filterType);
        if (list != null) return list;

        list = new ArrayList<ZuulFilter>();

        Collection<ZuulFilter> filters = filterRegistry.getAllFilters();
        for (Iterator<ZuulFilter> iterator = filters.iterator(); iterator.hasNext(); ) {
            ZuulFilter filter = iterator.next();
            if (filter.filterType().equals(filterType)) {
                list.add(filter);
            }
        }
        Collections.sort(list); // sort by priority

        hashFiltersByType.putIfAbsent(filterType, list);
        return list;
    }


    public static class TestZuulFilter extends ZuulFilter {

        public TestZuulFilter() {
            super();
        }

        @Override
        public String filterType() {
            return "test";
        }

        @Override
        public int filterOrder() {
            return 0;
        }

        public boolean shouldFilter() {
            return false;
        }

        public Object run() {
            return null;
        }
    }


    public static class UnitTest {

        @Mock
        File file;

        @Mock
        DynamicCodeCompiler compiler;

        @Mock
        FilterRegistry registry;

        FilterLoader loader;

        TestZuulFilter filter = new TestZuulFilter();

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);

            loader = spy(new FilterLoader());
            loader.setCompiler(compiler);
            loader.setFilterRegistry(registry);
        }

        @Test
        public void testGetFilterFromFile() throws Exception {
            doReturn(TestZuulFilter.class).when(compiler).compile(file);
            assertTrue(loader.putFilter(file));
            verify(registry).put(any(String.class), any(ZuulFilter.class));
        }

        @Test
        public void testGetFiltersByType() throws Exception {
            doReturn(TestZuulFilter.class).when(compiler).compile(file);
            assertTrue(loader.putFilter(file));

            verify(registry).put(any(String.class), any(ZuulFilter.class));

            final List<ZuulFilter> filters = new ArrayList<ZuulFilter>();
            filters.add(filter);
            when(registry.getAllFilters()).thenReturn(filters);

            List< ZuulFilter > list = loader.getFiltersByType("test");
            assertTrue(list != null);
            assertTrue(list.size() == 1);
            ZuulFilter filter = list.get(0);
            assertTrue(filter != null);
            assertTrue(filter.filterType().equals("test"));
        }


        @Test
        public void testGetFilterFromString() throws Exception {
            String string = "";
            doReturn(TestZuulFilter.class).when(compiler).compile(string, string);
            ZuulFilter filter = loader.getFilter(string, string);

            assertNotNull(filter);
            assertTrue(filter.getClass() == TestZuulFilter.class);
//            assertTrue(loader.filterInstanceMapSize() == 1);
        }


    }


}
