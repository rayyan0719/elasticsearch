/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.blobcache.shared;

import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.GroupedActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.blobcache.BlobCacheMetrics;
import org.elasticsearch.blobcache.common.ByteRange;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.RatioValue;
import org.elasticsearch.common.unit.RelativeByteSizeValue;
import org.elasticsearch.common.util.concurrent.DeterministicTaskQueue;
import org.elasticsearch.common.util.concurrent.StoppableExecutorServiceWrapper;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.node.NodeRoleSettings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.node.Node.NODE_NAME_SETTING;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class SharedBlobCacheServiceTests extends ESTestCase {

    private static long size(long numPages) {
        return numPages * SharedBytes.PAGE_SIZE;
    }

    public void testBasicEviction() throws IOException {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            final var cacheKey = generateCacheKey();
            assertEquals(5, cacheService.freeRegionCount());
            final var region0 = cacheService.get(cacheKey, size(250), 0);
            assertEquals(size(100), region0.tracker.getLength());
            assertEquals(4, cacheService.freeRegionCount());
            final var region1 = cacheService.get(cacheKey, size(250), 1);
            assertEquals(size(100), region1.tracker.getLength());
            assertEquals(3, cacheService.freeRegionCount());
            final var region2 = cacheService.get(cacheKey, size(250), 2);
            assertEquals(size(50), region2.tracker.getLength());
            assertEquals(2, cacheService.freeRegionCount());

            synchronized (cacheService) {
                assertTrue(region1.tryEvict());
            }
            assertEquals(3, cacheService.freeRegionCount());
            synchronized (cacheService) {
                assertFalse(region1.tryEvict());
            }
            assertEquals(3, cacheService.freeRegionCount());
            final var bytesReadFuture = new PlainActionFuture<Integer>();
            region0.populateAndRead(
                ByteRange.of(0L, 1L),
                ByteRange.of(0L, 1L),
                (channel, channelPos, relativePos, length) -> 1,
                (channel, channelPos, relativePos, length, progressUpdater) -> progressUpdater.accept(length),
                taskQueue.getThreadPool().generic(),
                bytesReadFuture
            );
            synchronized (cacheService) {
                assertFalse(region0.tryEvict());
            }
            assertEquals(3, cacheService.freeRegionCount());
            assertFalse(bytesReadFuture.isDone());
            taskQueue.runAllRunnableTasks();
            synchronized (cacheService) {
                assertTrue(region0.tryEvict());
            }
            assertEquals(4, cacheService.freeRegionCount());
            synchronized (cacheService) {
                assertTrue(region2.tryEvict());
            }
            assertEquals(5, cacheService.freeRegionCount());
            assertTrue(bytesReadFuture.isDone());
            assertEquals(Integer.valueOf(1), bytesReadFuture.actionGet());
        }
    }

    public void testAutoEviction() throws IOException {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(200)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            final var cacheKey = generateCacheKey();
            assertEquals(2, cacheService.freeRegionCount());
            final var region0 = cacheService.get(cacheKey, size(250), 0);
            assertEquals(size(100), region0.tracker.getLength());
            assertEquals(1, cacheService.freeRegionCount());
            final var region1 = cacheService.get(cacheKey, size(250), 1);
            assertEquals(size(100), region1.tracker.getLength());
            assertEquals(0, cacheService.freeRegionCount());
            assertFalse(region0.isEvicted());
            assertFalse(region1.isEvicted());

            // acquire region 2, which should evict region 0 (oldest)
            final var region2 = cacheService.get(cacheKey, size(250), 2);
            assertEquals(size(50), region2.tracker.getLength());
            assertEquals(0, cacheService.freeRegionCount());
            assertTrue(region0.isEvicted());
            assertFalse(region1.isEvicted());

            // explicitly evict region 1
            synchronized (cacheService) {
                assertTrue(region1.tryEvict());
            }
            assertEquals(1, cacheService.freeRegionCount());
        }
    }

    public void testForceEviction() throws IOException {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            final var cacheKey1 = generateCacheKey();
            final var cacheKey2 = generateCacheKey();
            assertEquals(5, cacheService.freeRegionCount());
            final var region0 = cacheService.get(cacheKey1, size(250), 0);
            assertEquals(4, cacheService.freeRegionCount());
            final var region1 = cacheService.get(cacheKey2, size(250), 1);
            assertEquals(3, cacheService.freeRegionCount());
            assertFalse(region0.isEvicted());
            assertFalse(region1.isEvicted());
            cacheService.removeFromCache(cacheKey1);
            assertTrue(region0.isEvicted());
            assertFalse(region1.isEvicted());
            assertEquals(4, cacheService.freeRegionCount());
        }
    }

    public void testForceEvictResponse() throws IOException {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            final var cacheKey1 = generateCacheKey();
            final var cacheKey2 = generateCacheKey();
            assertEquals(5, cacheService.freeRegionCount());
            final var region0 = cacheService.get(cacheKey1, size(250), 0);
            assertEquals(4, cacheService.freeRegionCount());
            final var region1 = cacheService.get(cacheKey2, size(250), 1);
            assertEquals(3, cacheService.freeRegionCount());
            assertFalse(region0.isEvicted());
            assertFalse(region1.isEvicted());

            assertEquals(1, cacheService.forceEvict(cK -> cK == cacheKey1));
            assertEquals(1, cacheService.forceEvict(e -> true));
        }
    }

    public void testDecay() throws IOException {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            final var cacheKey1 = generateCacheKey();
            final var cacheKey2 = generateCacheKey();
            assertEquals(5, cacheService.freeRegionCount());
            final var region0 = cacheService.get(cacheKey1, size(250), 0);
            assertEquals(4, cacheService.freeRegionCount());
            final var region1 = cacheService.get(cacheKey2, size(250), 1);
            assertEquals(3, cacheService.freeRegionCount());

            assertEquals(1, cacheService.getFreq(region0));
            assertEquals(1, cacheService.getFreq(region1));

            taskQueue.advanceTime();
            taskQueue.runAllRunnableTasks();

            final var region0Again = cacheService.get(cacheKey1, size(250), 0);
            assertSame(region0Again, region0);
            assertEquals(2, cacheService.getFreq(region0));
            assertEquals(1, cacheService.getFreq(region1));

            taskQueue.advanceTime();
            taskQueue.runAllRunnableTasks();
            cacheService.get(cacheKey1, size(250), 0);
            assertEquals(3, cacheService.getFreq(region0));
            cacheService.get(cacheKey1, size(250), 0);
            assertEquals(3, cacheService.getFreq(region0));

            // advance 2 ticks (decay only starts after 2 ticks)
            taskQueue.advanceTime();
            taskQueue.runAllRunnableTasks();
            taskQueue.advanceTime();
            taskQueue.runAllRunnableTasks();
            assertEquals(2, cacheService.getFreq(region0));
            assertEquals(0, cacheService.getFreq(region1));

            // advance another tick
            taskQueue.advanceTime();
            taskQueue.runAllRunnableTasks();
            assertEquals(1, cacheService.getFreq(region0));
            assertEquals(0, cacheService.getFreq(region1));

            // advance another tick
            taskQueue.advanceTime();
            taskQueue.runAllRunnableTasks();
            assertEquals(0, cacheService.getFreq(region0));
            assertEquals(0, cacheService.getFreq(region1));
        }
    }

    /**
     * Exercise SharedBlobCacheService#get in multiple threads to trigger any assertion errors.
     * @throws IOException
     */
    public void testGetMultiThreaded() throws IOException {
        int threads = between(2, 10);
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(
                SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(),
                ByteSizeValue.ofBytes(size(between(1, 20) * 100L)).getStringRep()
            )
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_MIN_TIME_DELTA_SETTING.getKey(), randomFrom("0", "1ms", "10s"))
            .put("path.home", createTempDir())
            .build();
        long fileLength = size(500);
        ThreadPool threadPool = new TestThreadPool("testGetMultiThreaded");
        Set<String> files = randomSet(1, 10, () -> randomAlphaOfLength(5));
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<String>(
                environment,
                settings,
                threadPool,
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            CyclicBarrier ready = new CyclicBarrier(threads);
            List<Thread> threadList = IntStream.range(0, threads).mapToObj(no -> {
                int iterations = between(100, 500);
                String[] cacheKeys = IntStream.range(0, iterations).mapToObj(ignore -> randomFrom(files)).toArray(String[]::new);
                int[] regions = IntStream.range(0, iterations).map(ignore -> between(0, 4)).toArray();
                int[] yield = IntStream.range(0, iterations).map(ignore -> between(0, 9)).toArray();
                int[] evict = IntStream.range(0, iterations).map(ignore -> between(0, 99)).toArray();
                return new Thread(() -> {
                    try {
                        ready.await();
                        for (int i = 0; i < iterations; ++i) {
                            try {
                                SharedBlobCacheService<String>.CacheFileRegion cacheFileRegion = cacheService.get(
                                    cacheKeys[i],
                                    fileLength,
                                    regions[i]
                                );
                                if (cacheFileRegion.tryIncRef()) {
                                    if (yield[i] == 0) {
                                        Thread.yield();
                                    }
                                    cacheFileRegion.decRef();
                                }
                                if (evict[i] == 0) {
                                    cacheService.forceEvict(x -> true);
                                }
                            } catch (AlreadyClosedException e) {
                                // ignore
                            }
                        }
                    } catch (InterruptedException | BrokenBarrierException e) {
                        assert false;
                        throw new RuntimeException(e);
                    }
                });
            }).toList();
            threadList.forEach(Thread::start);
            threadList.forEach(thread -> {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        } finally {
            threadPool.shutdownNow();
        }
    }

    public void testFetchFullCacheEntry() throws Exception {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();

        AtomicInteger bulkTaskCount = new AtomicInteger(0);
        ThreadPool threadPool = new TestThreadPool("test") {
            @Override
            public ExecutorService executor(String name) {
                ExecutorService generic = super.executor(Names.GENERIC);
                if (Objects.equals(name, "bulk")) {
                    return new StoppableExecutorServiceWrapper(generic) {
                        @Override
                        public void execute(Runnable command) {
                            super.execute(command);
                            bulkTaskCount.incrementAndGet();
                        }
                    };
                }
                return generic;
            }
        };

        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                threadPool,
                ThreadPool.Names.GENERIC,
                "bulk",
                BlobCacheMetrics.NOOP,
                threadPool::relativeTimeInMillis
            )
        ) {
            {
                final var cacheKey = generateCacheKey();
                assertEquals(5, cacheService.freeRegionCount());
                final long size = size(250);
                AtomicLong bytesRead = new AtomicLong(size);
                final PlainActionFuture<Void> future = new PlainActionFuture<>();
                cacheService.maybeFetchFullEntry(cacheKey, size, (channel, channelPos, relativePos, length, progressUpdater) -> {
                    bytesRead.addAndGet(-length);
                    progressUpdater.accept(length);
                }, future);

                future.get(10, TimeUnit.SECONDS);
                assertEquals(0L, bytesRead.get());
                assertEquals(2, cacheService.freeRegionCount());
                assertEquals(3, bulkTaskCount.get());
            }
            {
                // a download that would use up all regions should not run
                final var cacheKey = generateCacheKey();
                assertEquals(2, cacheService.freeRegionCount());
                var configured = cacheService.maybeFetchFullEntry(cacheKey, size(500), (ch, chPos, relPos, len, update) -> {
                    throw new AssertionError("Should never reach here");
                }, ActionListener.noop());
                assertFalse(configured);
                assertEquals(2, cacheService.freeRegionCount());
            }
        }

        threadPool.shutdown();
    }

    public void testFetchFullCacheEntryConcurrently() throws Exception {
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();

        ThreadPool threadPool = new TestThreadPool("test") {
            @Override
            public ExecutorService executor(String name) {
                ExecutorService generic = super.executor(Names.GENERIC);
                if (Objects.equals(name, "bulk")) {
                    return new StoppableExecutorServiceWrapper(generic);
                }
                return generic;
            }
        };

        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                threadPool,
                ThreadPool.Names.GENERIC,
                "bulk",
                BlobCacheMetrics.NOOP,
                threadPool::relativeTimeInMillis
            )
        ) {

            final long size = size(randomIntBetween(1, 100));
            final Thread[] threads = new Thread[10];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < 1000; j++) {
                        final var cacheKey = generateCacheKey();
                        try {
                            PlainActionFuture.<Void, Exception>get(
                                f -> cacheService.maybeFetchFullEntry(
                                    cacheKey,
                                    size,
                                    (channel, channelPos, relativePos, length, progressUpdater) -> progressUpdater.accept(length),
                                    f
                                )
                            );
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    }
                });
            }
            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
        } finally {
            assertTrue(ThreadPool.terminate(threadPool, 10L, TimeUnit.SECONDS));
        }
    }

    public void testCacheSizeRejectedOnNonFrozenNodes() {
        String cacheSize = randomBoolean()
            ? ByteSizeValue.ofBytes(size(500)).getStringRep()
            : (new RatioValue(between(1, 100))).formatNoTrailingZerosPercent();
        final Settings settings = Settings.builder()
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), cacheSize)
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .putList(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), DiscoveryNodeRole.DATA_HOT_NODE_ROLE.roleName())
            .build();
        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.get(settings)
        );
        assertThat(e.getCause(), notNullValue());
        assertThat(e.getCause(), instanceOf(SettingsException.class));
        assertThat(
            e.getCause().getMessage(),
            is(
                "Setting ["
                    + SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey()
                    + "] to be positive ["
                    + cacheSize
                    + "] is only permitted on nodes with the data_frozen, search, or indexing role. Roles are [data_hot]"
            )
        );
    }

    public void testMultipleDataPathsRejectedOnFrozenNodes() {
        final Settings settings = Settings.builder()
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(500)).getStringRep())
            .putList(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE.roleName())
            .putList(Environment.PATH_DATA_SETTING.getKey(), List.of("a", "b"))
            .build();
        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.get(settings)
        );
        assertThat(e.getCause(), notNullValue());
        assertThat(e.getCause(), instanceOf(SettingsException.class));
        assertThat(
            e.getCause().getMessage(),
            is(
                "setting ["
                    + SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey()
                    + "="
                    + ByteSizeValue.ofBytes(size(500)).getStringRep()
                    + "] is not permitted on nodes with multiple data paths [a,b]"
            )
        );
    }

    public void testDedicateFrozenCacheSizeDefaults() {
        final Settings settings = Settings.builder()
            .putList(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE.roleName())
            .build();

        RelativeByteSizeValue relativeCacheSize = SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.get(settings);
        assertThat(relativeCacheSize.isAbsolute(), is(false));
        assertThat(relativeCacheSize.isNonZeroSize(), is(true));
        assertThat(relativeCacheSize.calculateValue(ByteSizeValue.ofBytes(10000), null), equalTo(ByteSizeValue.ofBytes(9000)));
        assertThat(SharedBlobCacheService.SHARED_CACHE_SIZE_MAX_HEADROOM_SETTING.get(settings), equalTo(ByteSizeValue.ofGb(100)));
    }

    public void testNotDedicatedFrozenCacheSizeDefaults() {
        final Settings settings = Settings.builder()
            .putList(
                NodeRoleSettings.NODE_ROLES_SETTING.getKey(),
                Sets.union(
                    Set.of(
                        randomFrom(
                            DiscoveryNodeRole.DATA_HOT_NODE_ROLE,
                            DiscoveryNodeRole.DATA_COLD_NODE_ROLE,
                            DiscoveryNodeRole.DATA_WARM_NODE_ROLE,
                            DiscoveryNodeRole.DATA_CONTENT_NODE_ROLE
                        )
                    ),
                    new HashSet<>(
                        randomSubsetOf(
                            between(0, 3),
                            DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE,
                            DiscoveryNodeRole.INGEST_ROLE,
                            DiscoveryNodeRole.MASTER_ROLE
                        )
                    )
                ).stream().map(DiscoveryNodeRole::roleName).collect(Collectors.toList())
            )
            .build();

        RelativeByteSizeValue relativeCacheSize = SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.get(settings);
        assertThat(relativeCacheSize.isNonZeroSize(), is(false));
        assertThat(relativeCacheSize.isAbsolute(), is(true));
        assertThat(relativeCacheSize.getAbsolute(), equalTo(ByteSizeValue.ZERO));
        assertThat(SharedBlobCacheService.SHARED_CACHE_SIZE_MAX_HEADROOM_SETTING.get(settings), equalTo(ByteSizeValue.ofBytes(-1)));
    }

    public void testSearchOrIndexNodeCacheSizeDefaults() {
        final Settings settings = Settings.builder()
            .putList(
                NodeRoleSettings.NODE_ROLES_SETTING.getKey(),
                randomFrom(DiscoveryNodeRole.SEARCH_ROLE, DiscoveryNodeRole.INDEX_ROLE).roleName()
            )
            .build();

        RelativeByteSizeValue relativeCacheSize = SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.get(settings);
        assertThat(relativeCacheSize.isAbsolute(), is(false));
        assertThat(relativeCacheSize.isNonZeroSize(), is(true));
        assertThat(relativeCacheSize.calculateValue(ByteSizeValue.ofBytes(10000), null), equalTo(ByteSizeValue.ofBytes(9000)));
        assertThat(SharedBlobCacheService.SHARED_CACHE_SIZE_MAX_HEADROOM_SETTING.get(settings), equalTo(ByteSizeValue.ofGb(100)));
    }

    public void testMaxHeadroomRejectedForAbsoluteCacheSize() {
        String cacheSize = ByteSizeValue.ofBytes(size(500)).getStringRep();
        final Settings settings = Settings.builder()
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), cacheSize)
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_MAX_HEADROOM_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .putList(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE.roleName())
            .build();
        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> SharedBlobCacheService.SHARED_CACHE_SIZE_MAX_HEADROOM_SETTING.get(settings)
        );
        assertThat(e.getCause(), notNullValue());
        assertThat(e.getCause(), instanceOf(SettingsException.class));
        assertThat(
            e.getCause().getMessage(),
            is(
                "setting ["
                    + SharedBlobCacheService.SHARED_CACHE_SIZE_MAX_HEADROOM_SETTING.getKey()
                    + "] cannot be specified for absolute ["
                    + SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey()
                    + "="
                    + cacheSize
                    + "]"
            )
        );
    }

    public void testCalculateCacheSize() {
        long smallSize = 10000;
        long largeSize = ByteSizeValue.ofTb(10).getBytes();
        assertThat(SharedBlobCacheService.calculateCacheSize(Settings.EMPTY, smallSize), equalTo(0L));
        final Settings settings = Settings.builder()
            .putList(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE.roleName())
            .build();
        assertThat(SharedBlobCacheService.calculateCacheSize(settings, smallSize), equalTo(9000L));
        assertThat(SharedBlobCacheService.calculateCacheSize(settings, largeSize), equalTo(largeSize - ByteSizeValue.ofGb(100).getBytes()));
    }

    private static Object generateCacheKey() {
        return new Object();
    }

    public void testCacheSizeChanges() throws IOException {
        ByteSizeValue val1 = new ByteSizeValue(randomIntBetween(1, 5), ByteSizeUnit.MB);
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), val1.getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put("path.home", createTempDir())
            .build();
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            SharedBlobCacheService<?> cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            assertEquals(val1.getBytes(), cacheService.getStats().size());
        }

        ByteSizeValue val2 = new ByteSizeValue(randomIntBetween(1, 5), ByteSizeUnit.MB);
        settings = Settings.builder()
            .put(settings)
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), val2.getStringRep())
            .build();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            SharedBlobCacheService<?> cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP
            )
        ) {
            assertEquals(val2.getBytes(), cacheService.getStats().size());
        }
    }

    public void testMaybeEvictLeastUsed() throws Exception {
        final int numRegions = 3;
        randomIntBetween(1, 500);
        final long regionSize = size(1L);
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(numRegions)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(regionSize).getStringRep())
            .put("path.home", createTempDir())
            .build();

        final AtomicLong relativeTimeInMillis = new AtomicLong(0L);
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                "bulk",
                BlobCacheMetrics.NOOP,
                relativeTimeInMillis::get
            )
        ) {
            final Set<Object> cacheKeys = new HashSet<>();

            assertThat("All regions are free", cacheService.freeRegionCount(), equalTo(numRegions));
            assertThat("Cache has no entries", cacheService.maybeEvictLeastUsed(), is(false));

            // use all regions in cache
            for (int i = 0; i < numRegions; i++) {
                final var cacheKey = generateCacheKey();
                var entry = cacheService.get(cacheKey, regionSize, 0);
                entry.populate(
                    ByteRange.of(0L, regionSize),
                    (channel, channelPos, relativePos, length, progressUpdater) -> progressUpdater.accept(length),
                    taskQueue.getThreadPool().generic(),
                    ActionListener.noop()
                );
                assertThat(cacheService.getFreq(entry), equalTo(1));
                relativeTimeInMillis.incrementAndGet();
                cacheKeys.add(cacheKey);
            }

            assertThat("All regions are used", cacheService.freeRegionCount(), equalTo(0));
            assertThat("Cache entries are not old enough to be evicted", cacheService.maybeEvictLeastUsed(), is(false));

            taskQueue.runAllRunnableTasks();

            assertThat("All regions are used", cacheService.freeRegionCount(), equalTo(0));
            assertThat("Cache entries are not old enough to be evicted", cacheService.maybeEvictLeastUsed(), is(false));

            // simulate elapsed time
            var minInternalMillis = SharedBlobCacheService.SHARED_CACHE_MIN_TIME_DELTA_SETTING.getDefault(Settings.EMPTY).millis();
            relativeTimeInMillis.addAndGet(minInternalMillis);

            // touch some random cache entries
            var unusedCacheKeys = Set.copyOf(randomSubsetOf(cacheKeys));
            cacheKeys.forEach(key -> {
                if (unusedCacheKeys.contains(key) == false) {
                    var entry = cacheService.get(key, regionSize, 0);
                    assertThat(cacheService.getFreq(entry), equalTo(2));
                }
            });

            assertThat("All regions are used", cacheService.freeRegionCount(), equalTo(0));
            assertThat("Cache entries are not old enough to be evicted", cacheService.maybeEvictLeastUsed(), is(false));

            for (int i = 1; i <= unusedCacheKeys.size(); i++) {
                // need to advance time and compute decay to decrease frequencies in cache and have an evictable entry
                relativeTimeInMillis.addAndGet(minInternalMillis);
                cacheService.computeDecay();

                assertThat("Cache entry is old enough to be evicted", cacheService.maybeEvictLeastUsed(), is(true));
                assertThat(cacheService.freeRegionCount(), equalTo(i));
            }

            assertThat("No more cache entries old enough to be evicted", cacheService.maybeEvictLeastUsed(), is(false));
            assertThat(cacheService.freeRegionCount(), equalTo(unusedCacheKeys.size()));
        }
    }

    public void testMaybeFetchRegion() throws Exception {
        final long cacheSize = size(500L);
        final long regionSize = size(100L);
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(cacheSize).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(regionSize).getStringRep())
            .put("path.home", createTempDir())
            .build();

        AtomicInteger bulkTaskCount = new AtomicInteger(0);
        ThreadPool threadPool = new TestThreadPool("test") {
            @Override
            public ExecutorService executor(String name) {
                ExecutorService generic = super.executor(Names.GENERIC);
                if (Objects.equals(name, "bulk")) {
                    return new StoppableExecutorServiceWrapper(generic) {
                        @Override
                        public void execute(Runnable command) {
                            super.execute(command);
                            bulkTaskCount.incrementAndGet();
                        }
                    };
                }
                return generic;
            }
        };
        final AtomicLong relativeTimeInMillis = new AtomicLong(0L);
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                threadPool,
                ThreadPool.Names.GENERIC,
                "bulk",
                BlobCacheMetrics.NOOP,
                relativeTimeInMillis::get
            )
        ) {
            {
                // fetch a single region
                final var cacheKey = generateCacheKey();
                assertEquals(5, cacheService.freeRegionCount());
                final long blobLength = size(250); // 3 regions
                AtomicLong bytesRead = new AtomicLong(0L);
                final PlainActionFuture<Boolean> future = new PlainActionFuture<>();
                cacheService.maybeFetchRegion(cacheKey, 0, blobLength, (channel, channelPos, relativePos, length, progressUpdater) -> {
                    bytesRead.addAndGet(length);
                    progressUpdater.accept(length);
                }, future);

                var fetched = future.get(10, TimeUnit.SECONDS);
                assertThat("Region has been fetched", fetched, is(true));
                assertEquals(regionSize, bytesRead.get());
                assertEquals(4, cacheService.freeRegionCount());
                assertEquals(1, bulkTaskCount.get());
            }
            {
                // fetch multiple regions to used all the cache
                final int remainingFreeRegions = cacheService.freeRegionCount();
                assertEquals(4, cacheService.freeRegionCount());

                final var cacheKey = generateCacheKey();
                final long blobLength = regionSize * remainingFreeRegions;
                AtomicLong bytesRead = new AtomicLong(0L);

                final PlainActionFuture<Collection<Boolean>> future = new PlainActionFuture<>();
                final var listener = new GroupedActionListener<>(remainingFreeRegions, future);
                for (int region = 0; region < remainingFreeRegions; region++) {
                    relativeTimeInMillis.addAndGet(1_000L);
                    cacheService.maybeFetchRegion(
                        cacheKey,
                        region,
                        blobLength,
                        (channel, channelPos, relativePos, length, progressUpdater) -> {
                            bytesRead.addAndGet(length);
                            progressUpdater.accept(length);
                        },
                        listener
                    );
                }

                var results = future.get(10, TimeUnit.SECONDS);
                assertThat(results.stream().allMatch(result -> result), is(true));
                assertEquals(blobLength, bytesRead.get());
                assertEquals(0, cacheService.freeRegionCount());
                assertEquals(1 + remainingFreeRegions, bulkTaskCount.get());
            }
            {
                // cache fully used, no entry old enough to be evicted
                assertEquals(0, cacheService.freeRegionCount());
                final var cacheKey = generateCacheKey();
                final PlainActionFuture<Boolean> future = new PlainActionFuture<>();
                cacheService.maybeFetchRegion(
                    cacheKey,
                    randomIntBetween(0, 10),
                    randomLongBetween(1L, regionSize),
                    (channel, channelPos, relativePos, length, progressUpdater) -> {
                        throw new AssertionError("should not be executed");
                    },
                    future
                );
                assertThat("Listener is immediately completed", future.isDone(), is(true));
                assertThat("Region already exists in cache", future.get(), is(false));
            }
            {
                // simulate elapsed time and compute decay
                var minInternalMillis = SharedBlobCacheService.SHARED_CACHE_MIN_TIME_DELTA_SETTING.getDefault(Settings.EMPTY).millis();
                relativeTimeInMillis.addAndGet(minInternalMillis * 2);
                cacheService.computeDecay();

                // fetch one more region should evict an old cache entry
                final var cacheKey = generateCacheKey();
                assertEquals(0, cacheService.freeRegionCount());
                long blobLength = randomLongBetween(1L, regionSize);
                AtomicLong bytesRead = new AtomicLong(0L);
                final PlainActionFuture<Boolean> future = new PlainActionFuture<>();
                cacheService.maybeFetchRegion(cacheKey, 0, blobLength, (channel, channelPos, relativePos, length, progressUpdater) -> {
                    bytesRead.addAndGet(length);
                    progressUpdater.accept(length);
                }, future);

                var fetched = future.get(10, TimeUnit.SECONDS);
                assertThat("Region has been fetched", fetched, is(true));
                assertEquals(blobLength, bytesRead.get());
                assertEquals(0, cacheService.freeRegionCount());
            }
        }

        threadPool.shutdown();
    }

    public void testPopulate() throws Exception {
        final long regionSize = size(1L);
        Settings settings = Settings.builder()
            .put(NODE_NAME_SETTING.getKey(), "node")
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .put(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(regionSize).getStringRep())
            .put("path.home", createTempDir())
            .build();

        final AtomicLong relativeTimeInMillis = new AtomicLong(0L);
        final DeterministicTaskQueue taskQueue = new DeterministicTaskQueue();
        try (
            NodeEnvironment environment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings));
            var cacheService = new SharedBlobCacheService<>(
                environment,
                settings,
                taskQueue.getThreadPool(),
                ThreadPool.Names.GENERIC,
                ThreadPool.Names.GENERIC,
                BlobCacheMetrics.NOOP,
                relativeTimeInMillis::get
            )
        ) {
            final var cacheKey = generateCacheKey();
            final var blobLength = size(12L);

            // start populating the first region
            var entry = cacheService.get(cacheKey, blobLength, 0);
            AtomicLong bytesWritten = new AtomicLong(0L);
            final PlainActionFuture<Boolean> future1 = new PlainActionFuture<>();
            entry.populate(ByteRange.of(0, regionSize - 1), (channel, channelPos, relativePos, length, progressUpdater) -> {
                bytesWritten.addAndGet(length);
                progressUpdater.accept(length);
            }, taskQueue.getThreadPool().generic(), future1);

            assertThat(future1.isDone(), is(false));
            assertThat(taskQueue.hasRunnableTasks(), is(true));

            // start populating the second region
            entry = cacheService.get(cacheKey, blobLength, 1);
            final PlainActionFuture<Boolean> future2 = new PlainActionFuture<>();
            entry.populate(ByteRange.of(0, regionSize - 1), (channel, channelPos, relativePos, length, progressUpdater) -> {
                bytesWritten.addAndGet(length);
                progressUpdater.accept(length);
            }, taskQueue.getThreadPool().generic(), future2);

            // start populating again the first region, listener should be called immediately
            entry = cacheService.get(cacheKey, blobLength, 0);
            final PlainActionFuture<Boolean> future3 = new PlainActionFuture<>();
            entry.populate(ByteRange.of(0, regionSize - 1), (channel, channelPos, relativePos, length, progressUpdater) -> {
                bytesWritten.addAndGet(length);
                progressUpdater.accept(length);
            }, taskQueue.getThreadPool().generic(), future3);

            assertThat(future3.isDone(), is(true));
            var written = future3.get(10L, TimeUnit.SECONDS);
            assertThat(written, is(false));

            taskQueue.runAllRunnableTasks();

            written = future1.get(10L, TimeUnit.SECONDS);
            assertThat(future1.isDone(), is(true));
            assertThat(written, is(true));
            written = future2.get(10L, TimeUnit.SECONDS);
            assertThat(future2.isDone(), is(true));
            assertThat(written, is(true));
        }
    }

    private void assertThatNonPositiveRecoveryRangeSizeRejected(Setting<ByteSizeValue> setting) {
        final String value = randomFrom(ByteSizeValue.MINUS_ONE, ByteSizeValue.ZERO).getStringRep();
        final Settings settings = Settings.builder()
            .put(SharedBlobCacheService.SHARED_CACHE_SIZE_SETTING.getKey(), ByteSizeValue.ofBytes(size(100)).getStringRep())
            .putList(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE.roleName())
            .put(setting.getKey(), value)
            .build();
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> setting.get(settings));
        assertThat(e.getCause(), notNullValue());
        assertThat(e.getCause(), instanceOf(SettingsException.class));
        assertThat(e.getCause().getMessage(), is("setting [" + setting.getKey() + "] must be greater than zero"));
    }

    public void testNonPositiveRegionSizeRejected() {
        assertThatNonPositiveRecoveryRangeSizeRejected(SharedBlobCacheService.SHARED_CACHE_REGION_SIZE_SETTING);
    }

    public void testNonPositiveRangeSizeRejected() {
        assertThatNonPositiveRecoveryRangeSizeRejected(SharedBlobCacheService.SHARED_CACHE_RANGE_SIZE_SETTING);
    }

    public void testNonPositiveRecoveryRangeSizeRejected() {
        assertThatNonPositiveRecoveryRangeSizeRejected(SharedBlobCacheService.SHARED_CACHE_RECOVERY_RANGE_SIZE_SETTING);
    }

}
