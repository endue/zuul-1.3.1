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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.*;

/**
 * This class manages the directory polling for changes and new Groovy filters.
 * Polling interval and directories are specified in the initialization of the class, and a poller will check
 * for changes and additions.
 *
 * @author Mikey Cohen
 *         Date: 12/7/11
 *         Time: 12:09 PM
 * zuul支持动加载Filter类文件。该类实现原理是监控存放Filter文件的目录，定期扫描这些目录，
 * 如果发现有新Filter源码文件或者Filter源码文件有改动，则对文件进行加载交给FilterLoader类处理
 */
public class FilterFileManager {

    private static final Logger LOG = LoggerFactory.getLogger(FilterFileManager.class);

    String[] aDirectories;// 默认"src/main/groovy/filters\pre"、"src/main/groovy/filters\route"、"src/main/groovy/filters\post"
    int pollingIntervalSeconds;// 默认5s
    Thread poller;// 定时任务启动的线程，每5s执行一次
    boolean bRunning = true;// 定时任务中while选择的判断条件

    // 文件名过滤器，默认GroovyFileFilter
    static FilenameFilter FILENAME_FILTER;

    static FilterFileManager INSTANCE;

    private FilterFileManager() {
    }

    public static void setFilenameFilter(FilenameFilter filter) {
        FILENAME_FILTER = filter;
    }

    /**
     * Initialized the GroovyFileManager.
     *
     * @param pollingIntervalSeconds the polling interval in Seconds
     *                               默认5
     * @param directories            Any number of paths to directories to be polled may be specified
     *                               默认"src/main/groovy/filters\pre"、"src/main/groovy/filters\route"、"src/main/groovy/filters\post"
     * @throws IOException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    public static void init(int pollingIntervalSeconds, String... directories) throws Exception, IllegalAccessException, InstantiationException {
        // 单例模式初始化
        if (INSTANCE == null) INSTANCE = new FilterFileManager();

        INSTANCE.aDirectories = directories;
        INSTANCE.pollingIntervalSeconds = pollingIntervalSeconds;
        // 立即读取aDirectories属性目录下的Groovy文件
        // 之后会启动一个定时任务再每隔5s执行一次
        INSTANCE.manageFiles();
        // 启动定时任务，每5s执行一次manageFiles()方法
        INSTANCE.startPoller();

    }

    public static FilterFileManager getInstance() {
        return INSTANCE;
    }

    /**
     * Shuts down the poller
     */
    public static void shutdown() {
        INSTANCE.stopPoller();
    }


    void stopPoller() {
        bRunning = false;
    }

    void startPoller() {
        poller = new Thread("GroovyFilterFileManagerPoller") {
            public void run() {
                while (bRunning) {
                    try {
                        // 睡眠5s
                        sleep(pollingIntervalSeconds * 1000);
                        manageFiles();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        poller.setDaemon(true);
        poller.start();
    }

    /**
     * Returns the directory File for a path. A Runtime Exception is thrown if the directory is in valid
     *
     * @param sPath
     * @return a File representing the directory path
     */
    public File getDirectory(String sPath) {
        File  directory = new File(sPath);
        if (!directory.isDirectory()) {
            URL resource = FilterFileManager.class.getClassLoader().getResource(sPath);
            try {
                directory = new File(resource.toURI());
            } catch (Exception e) {
                LOG.error("Error accessing directory in classloader. path=" + sPath, e);
            }
            if (!directory.isDirectory()) {
                throw new RuntimeException(directory.getAbsolutePath() + " is not a valid directory");
            }
        }
        return directory;
    }

    /**
     * Returns a List<File> of all Files from all polled directories
     *
     * @return
     */
    List<File> getFiles() {
        List<File> list = new ArrayList<File>();
        for (String sDirectory : aDirectories) {
            if (sDirectory != null) {
                // FILENAME_FILTER默认为GroovyFileFilter，然后会调用GroovyFileFilter的accept
                // 这里就是获取每个路径下的Groovy文件
                File directory = getDirectory(sDirectory);
                File[] aFiles = directory.listFiles(FILENAME_FILTER);
                if (aFiles != null) {
                    list.addAll(Arrays.asList(aFiles));
                }
            }
        }
        return list;
    }

    /**
     * puts files into the FilterLoader. The FilterLoader will only addd new or changed filters
     *
     * @param aFiles a List<File>
     * @throws IOException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    void processGroovyFiles(List<File> aFiles) throws Exception, InstantiationException, IllegalAccessException {
        // 把加载的文件交给FilterLoader来处理
        for (File file : aFiles) {
            FilterLoader.getInstance().putFilter(file);
        }
    }

    void manageFiles() throws Exception, IllegalAccessException, InstantiationException {
        List<File> aFiles = getFiles();
        processGroovyFiles(aFiles);
    }


    @RunWith(MockitoJUnitRunner.class)
    public static class UnitTest {
        @Mock
        private File nonGroovyFile;
        @Mock
        private File groovyFile;

        @Mock
        private File directory;

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);
        }


        @Test
        public void testFileManagerInit() throws Exception, InstantiationException, IllegalAccessException {
            FilterFileManager manager = new FilterFileManager();

            manager = spy(manager);
            manager.INSTANCE = manager;
            doNothing().when(manager.INSTANCE).manageFiles();
            manager.init(1, "test", "test1");
            verify(manager, atLeast(1)).manageFiles();
            verify(manager, times(1)).startPoller();
            assertNotNull(manager.poller);

        }

    }

}
