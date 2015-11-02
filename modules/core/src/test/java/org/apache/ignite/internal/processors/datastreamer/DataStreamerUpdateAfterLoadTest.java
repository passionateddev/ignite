/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.datastreamer;

import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.cache.CacheAtomicWriteOrderMode;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

import static org.apache.ignite.cache.CacheAtomicWriteOrderMode.CLOCK;
import static org.apache.ignite.cache.CacheAtomicWriteOrderMode.PRIMARY;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;

/**
 *
 */
public class DataStreamerUpdateAfterLoadTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    private boolean client;

    /** */
    private static final int NODES = 4;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(IP_FINDER);

        cfg.setClientMode(client);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        startGridsMultiThreaded(NODES - 1);

        client = true;

        startGrid(NODES - 1);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();

        super.afterTestsStopped();
    }

    /**
     * @throws Exception If failed.
     */
    public void testUpdateAfterLoad() throws Exception {
        Ignite ignite0 = ignite(0);

        for (CacheConfiguration<Integer, Integer> ccfg : cacheConfigurations()) {
            int key = 0;

            try (IgniteCache<Integer, Integer> cache = ignite0.createCache(ccfg)) {
                key = testLoadAndUpdate(cache.getName(), key, false);

                testLoadAndUpdate(cache.getName(), key, true);

                ignite0.destroyCache(cache.getName());
            }
        }
    }

    /**
     * @param cacheName Cache name.
     * @param key Key.
     * @param allowOverwrite Streamer flag.
     * @return Next key.
     * @throws Exception If failed.
     */
    private int testLoadAndUpdate(String cacheName, int key, boolean allowOverwrite) throws Exception {
        for (int loadNode = 0; loadNode < NODES; loadNode++) {
            Ignite loadIgnite = ignite(loadNode);

            for (int updateNode = 0; updateNode < NODES; updateNode++) {
                try (IgniteDataStreamer<Integer, Integer> streamer = loadIgnite.dataStreamer(cacheName)) {
                    streamer.allowOverwrite(allowOverwrite);

                    streamer.addData(key, key);
                }

                Ignite updateIgnite = ignite(updateNode);

                IgniteCache<Integer, Integer> cache = updateIgnite.cache(cacheName);

                if (allowOverwrite)
                    atomicClockModeDelay(cache);

                updateIgnite.cache(cacheName).put(key, key + 1);

                checkValue(key, key + 1, cacheName);

                key++;
            }
        }

        return key;
    }

    /**
     * @param key Key.
     * @param val Value.
     * @param cacheName Cache name.
     */
    private void checkValue(Integer key, Integer val, String cacheName) {
        for (int i = 0; i < NODES; i++) {
            IgniteCache<Integer, Integer> cache = ignite(i).cache(cacheName);

            assertEquals("Unexpected value " + i, val, cache.get(key));
        }
    }

    /**
     * @return Cache configurations to test.
     */
    private List<CacheConfiguration<Integer, Integer>> cacheConfigurations() {
        List<CacheConfiguration<Integer, Integer>> ccfgs = new ArrayList<>();

        ccfgs.add(cacheConfiguration(CacheAtomicityMode.ATOMIC, PRIMARY, 1, "cache-" + ccfgs.size()));
        ccfgs.add(cacheConfiguration(CacheAtomicityMode.ATOMIC, PRIMARY, 0, "cache-" + ccfgs.size()));
        ccfgs.add(cacheConfiguration(CacheAtomicityMode.ATOMIC, CLOCK, 1, "cache-" + ccfgs.size()));
        ccfgs.add(cacheConfiguration(CacheAtomicityMode.TRANSACTIONAL, null, 1, "cache-" + ccfgs.size()));
        ccfgs.add(cacheConfiguration(CacheAtomicityMode.TRANSACTIONAL, null, 0, "cache-" + ccfgs.size()));

        return ccfgs;
    }

    /**
     * @param atomicityMode Cache atomicity mode.
     * @param writeOrderMode Cache write order mode.
     * @param backups Number of backups.
     * @param name Cache name.
     * @return Cache configuration.
     */
    private CacheConfiguration<Integer, Integer> cacheConfiguration(CacheAtomicityMode atomicityMode,
        CacheAtomicWriteOrderMode writeOrderMode,
        int backups,
        String name) {
        CacheConfiguration<Integer, Integer> ccfg = new CacheConfiguration<>();

        ccfg.setName(name);
        ccfg.setAtomicityMode(atomicityMode);
        ccfg.setBackups(backups);
        ccfg.setAtomicWriteOrderMode(writeOrderMode);
        ccfg.setWriteSynchronizationMode(FULL_SYNC);

        return ccfg;
    }
}