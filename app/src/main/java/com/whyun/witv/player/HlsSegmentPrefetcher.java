package com.whyun.witv.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.SimpleCache;

import com.whyun.witv.WiTVApp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按播放器实际读取位置维护 ts 预取：请求 X 时预取 X+1..X+3，并清理 < X 的任务与磁盘缓存。
 * 使用 3 个固定 worker 槽位，不额外维护等待队列。
 */
@OptIn(markerClass = UnstableApi.class)
public final class HlsSegmentPrefetcher {

    private static final String TAG = "HlsSegmentPrefetcher";
    private static final int PREFETCH_SEGMENT_COUNT = 3;
    private static final int CACHE_BUFFER_BYTES = 32 * 1024;

    private enum WorkerState {
        IDLE,
        ASSIGNED,
        EXECUTING,
        COMPLETED
    }

    /** ts 分片共享磁盘缓存。 */
    private final SimpleCache cache;
    /** 播放器正常回源读取时使用的数据源工厂。 */
    private final DataSource.Factory upstreamFactory;
    private final CacheKeyFactory cacheKeyFactory = dataSpec -> buildCacheKey(dataSpec.uri);
    /** 预取下载专用的数据源工厂，写入 {@link #cache}。 */
    private final CacheDataSource.Factory downloadCacheDataSourceFactory;
    /** 当前播放源在本轮生命周期里触达过的分片 key，用于切源时统一清理磁盘缓存。 */
    private final Set<String> currentSourceCacheKeys = ConcurrentHashMap.newKeySet();
    /** 保护最新直播窗口快照，避免 playlist 刷新与播放线程并发读写。 */
    private final Object liveWindowLock = new Object();
    /** 每次切源递增；旧 generation 的 worker 结果会被丢弃。 */
    private final AtomicInteger playbackSourceGeneration = new AtomicInteger();
    /** 固定 3 个 worker 槽位，分别承载当前 X+1..X+3 的预取任务。 */
    private final WorkerSlot[] workerSlots = new WorkerSlot[PREFETCH_SEGMENT_COUNT];
    /** 最新 playlist 中解析出的窗口分片 URI，按播放顺序排列。 */
    private List<Uri> currentLiveWindowSegmentUris = Collections.emptyList();
    /** 与 {@link #currentLiveWindowSegmentUris} 对应的 cache key 列表。 */
    private List<String> currentLiveWindowCacheKeys = Collections.emptyList();
    /** cache key 到窗口位置 X 的映射，便于播放器请求当前分片时定位游标。 */
    private Map<String, Integer> currentLiveWindowIndexByCacheKey = Collections.emptyMap();

    public HlsSegmentPrefetcher(@NonNull Context context, @NonNull DataSource.Factory upstreamFactory) {
        WiTVApp app = (WiTVApp) context.getApplicationContext();
        cache = app.getOrCreateMediaCache();
        this.upstreamFactory = upstreamFactory;
        downloadCacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory(cacheKeyFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        for (int i = 0; i < workerSlots.length; i++) {
            workerSlots[i] = new WorkerSlot(i);
            workerSlots[i].start();
        }
    }

    @NonNull
    public DataSource.Factory getPlaybackDataSourceFactory() {
        return this::createPlaybackCacheDataSource;
    }

    public void onPlaybackSourceChanged(@NonNull Uri playbackUri) {
        int generation = playbackSourceGeneration.incrementAndGet();
        Log.d(TAG, "Playback source changed, clear tracked cache: generation="
                + generation + ", uri=" + playbackUri);
        resetLiveWindowTracking();
        cancelAllPrefetchTasks();
        clearCurrentSourceCache();
    }

    public void updateLiveWindow(@NonNull Uri playlistUri, @NonNull String playlistText) {
        List<Uri> liveWindowSegmentUris = HlsSegmentPrefetchPlanner.extractSegmentUris(
                playlistUri, playlistText);
        List<String> liveWindowCacheKeys = new ArrayList<>(liveWindowSegmentUris.size());
        Map<String, Integer> indexByCacheKey = new HashMap<>();
        for (int i = 0; i < liveWindowSegmentUris.size(); i++) {
            Uri liveWindowSegmentUri = liveWindowSegmentUris.get(i);
            String cacheKey = buildCacheKey(liveWindowSegmentUri);
            if (cacheKey.isEmpty()) {
                continue;
            }
            liveWindowCacheKeys.add(cacheKey);
            indexByCacheKey.put(cacheKey, i);
            currentSourceCacheKeys.add(cacheKey);
        }
        synchronized (liveWindowLock) {
            currentLiveWindowSegmentUris = new ArrayList<>(liveWindowSegmentUris);
            currentLiveWindowCacheKeys = liveWindowCacheKeys;
            currentLiveWindowIndexByCacheKey = indexByCacheKey;
        }
        Log.d(TAG, "Live window updated: playlist=" + playlistUri
                + ", segmentCount=" + liveWindowCacheKeys.size());
    }

    private void resetLiveWindowTracking() {
        synchronized (liveWindowLock) {
            currentLiveWindowSegmentUris = Collections.emptyList();
            currentLiveWindowCacheKeys = Collections.emptyList();
            currentLiveWindowIndexByCacheKey = Collections.emptyMap();
        }
    }

    private boolean onPlaybackSegmentRequested(@NonNull Uri segmentUri) {
        String cacheKey = buildCacheKey(segmentUri);
        if (cacheKey.isEmpty() || !isTsSegmentUri(segmentUri)) {
            return false;
        }
        currentSourceCacheKeys.add(cacheKey);
        boolean busy = cancelPrefetchForPlayback(cacheKey, segmentUri);
        if (busy) {
            clearCachedResource(cacheKey, "Playback bypass unfinished prefetch");
        }
        int currentIndex = getLiveWindowIndex(cacheKey);
        if (currentIndex < 0) {
            Log.d(TAG, "Playback segment not found in latest live window: " + segmentUri);
            return busy;
        }
        Log.d(TAG, "Playback cursor: X=" + currentIndex
                + ", current=" + compactSegmentLabel(cacheKey)
                + ", bypassCache=" + busy
                + ", windowSize=" + getLiveWindowSize());
        clearSegmentsBeforeIndex(currentIndex);
        reconcileWorkersForPlaybackIndex(currentIndex);
        return busy;
    }

    private int getLiveWindowIndex(@NonNull String cacheKey) {
        synchronized (liveWindowLock) {
            Integer index = currentLiveWindowIndexByCacheKey.get(cacheKey);
            return index != null ? index : -1;
        }
    }

    private void clearSegmentsBeforeIndex(int currentIndex) {
        List<String> staleCacheKeys;
        synchronized (liveWindowLock) {
            if (currentIndex <= 0 || currentLiveWindowCacheKeys.isEmpty()) {
                return;
            }
            staleCacheKeys = new ArrayList<>(currentLiveWindowCacheKeys.subList(0, currentIndex));
        }
        Log.d(TAG, "Evict stale segments before X=" + currentIndex
                + ": " + summarizeCacheKeys(staleCacheKeys));
        for (String staleCacheKey : staleCacheKeys) {
            for (WorkerSlot workerSlot : workerSlots) {
                workerSlot.cancelIfHandling(staleCacheKey, "Drop stale segment before playback cursor");
            }
            clearCachedResource(staleCacheKey, "Evict stale segment before playback cursor");
        }
    }

    private void reconcileWorkersForPlaybackIndex(int currentIndex) {
        List<Uri> desiredSegmentUris = new ArrayList<>(PREFETCH_SEGMENT_COUNT);
        synchronized (liveWindowLock) {
            if (currentLiveWindowSegmentUris.isEmpty()) {
                clearAllWorkers("No live window segments to prefetch");
                return;
            }
            int endExclusive = Math.min(
                    currentLiveWindowSegmentUris.size(),
                    currentIndex + 1 + PREFETCH_SEGMENT_COUNT);
            for (int i = currentIndex + 1; i < endExclusive; i++) {
                Uri segmentUri = currentLiveWindowSegmentUris.get(i);
                desiredSegmentUris.add(segmentUri);
                currentSourceCacheKeys.add(buildCacheKey(segmentUri));
            }
        }
        if (desiredSegmentUris.isEmpty()) {
            clearAllWorkers("No forward segments after playback cursor");
            return;
        }
        Log.d(TAG, "Prefetch targets for X=" + currentIndex + ": "
                + summarizeUris(desiredSegmentUris));

        int generation = playbackSourceGeneration.get();
        Map<String, Uri> desiredByCacheKey = new LinkedHashMap<>();
        for (Uri desiredSegmentUri : desiredSegmentUris) {
            desiredByCacheKey.put(buildCacheKey(desiredSegmentUri), desiredSegmentUri);
        }

        boolean[] keptSlots = new boolean[workerSlots.length];
        for (int i = 0; i < workerSlots.length; i++) {
            WorkerAssignment assignment = workerSlots[i].snapshot();
            if (assignment.isSameGeneration(generation)
                    && desiredByCacheKey.containsKey(assignment.cacheKey)) {
                keptSlots[i] = true;
                desiredByCacheKey.remove(assignment.cacheKey);
            }
        }

        List<Uri> remainingUris = new ArrayList<>(desiredByCacheKey.values());
        int cursor = 0;
        for (int i = 0; i < workerSlots.length; i++) {
            if (keptSlots[i]) {
                continue;
            }
            if (cursor < remainingUris.size()) {
                workerSlots[i].assign(remainingUris.get(cursor++), generation);
            } else {
                workerSlots[i].clear("Segment no longer in X+1..X+3 target set");
            }
        }
        logWorkerState("Worker state after reconcile");
    }

    private void runPrefetch(@NonNull WorkerSlot workerSlot,
                             @NonNull WorkerAssignment assignment) {
        String cacheKey = assignment.cacheKey;
        Uri segmentUri = assignment.segmentUri;
        int generation = assignment.generation;
        boolean evictPartialCache = false;
        try {
            if (cacheKey.isEmpty() || segmentUri == null) {
                return;
            }
            if (generation != playbackSourceGeneration.get()) {
                Log.d(TAG, "Skip stale worker assignment after source changed: " + segmentUri);
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                evictPartialCache = true;
                Log.d(TAG, "Prefetch cancelled before start: " + segmentUri);
                return;
            }
            if (isAlreadyCached(cacheKey)) {
                workerSlot.markCompleted(cacheKey, generation);
                Log.d(TAG, "Skip prefetch because segment already cached: " + segmentUri);
                return;
            }
            workerSlot.markExecuting(cacheKey, generation);
            Log.d(TAG, "Prefetch start: " + segmentUri);
            DataSpec dataSpec = new DataSpec.Builder()
                    .setUri(segmentUri)
                    .setKey(cacheKey)
                    .build();
            CacheWriter cacheWriter = new CacheWriter(
                    downloadCacheDataSourceFactory.createDataSourceForDownloading(),
                    dataSpec,
                    new byte[CACHE_BUFFER_BYTES],
                    null);
            cacheWriter.cache();
            if (generation != playbackSourceGeneration.get()) {
                evictPartialCache = true;
                Log.d(TAG, "Prefetch finished for stale source, discard result: " + segmentUri);
                return;
            }
            if (!workerSlot.isStillAssigned(assignment)) {
                evictPartialCache = true;
                Log.d(TAG, "Prefetch finished after worker reassigned, discard result: " + segmentUri);
                return;
            }
            workerSlot.markCompleted(cacheKey, generation);
            Log.d(TAG, "Prefetch success: " + segmentUri);
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || isCancellationException(e)) {
                evictPartialCache = true;
                Log.d(TAG, "Prefetch cancelled: " + segmentUri + ", reason="
                        + cancellationReasonName(e));
                return;
            }
            Log.d(TAG, "Prefetch failed: " + segmentUri, e);
        } finally {
            workerSlot.clearExecuting(cacheKey, generation);
            if (evictPartialCache || generation != playbackSourceGeneration.get()) {
                clearCachedResource(cacheKey, "Drop partial cache after cancelled/stale prefetch");
            }
        }
    }

    private boolean cancelPrefetchForPlayback(@NonNull String cacheKey, @NonNull Uri segmentUri) {
        boolean cancelled = false;
        for (WorkerSlot workerSlot : workerSlots) {
            cancelled |= workerSlot.cancelIfHandling(cacheKey, "Playback needs current segment now");
        }
        if (cancelled) {
            Log.d(TAG, "Playback bypass cache for in-flight segment: " + segmentUri);
        }
        return cancelled;
    }

    private static boolean isCancellationException(@NonNull Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InterruptedIOException
                    || cursor instanceof InterruptedException
                    || cursor instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    @NonNull
    private static String cancellationReasonName(@NonNull Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InterruptedIOException) {
                return "INTERRUPTED_IO";
            }
            if (cursor instanceof InterruptedException) {
                return "INTERRUPTED";
            }
            if (cursor instanceof java.util.concurrent.CancellationException) {
                return "FUTURE_CANCELLED";
            }
            cursor = cursor.getCause();
        }
        return "INTERRUPTED";
    }

    private void cancelAllPrefetchTasks() {
        clearAllWorkers("Cancel all prefetch tasks");
    }

    private void clearAllWorkers(@NonNull String reason) {
        for (WorkerSlot workerSlot : workerSlots) {
            workerSlot.clear(reason);
        }
        logWorkerState("Worker state after clear");
    }

    private void clearCurrentSourceCache() {
        List<String> cacheKeys = new ArrayList<>(currentSourceCacheKeys);
        for (String cacheKey : cacheKeys) {
            clearCachedResource(cacheKey, "Clear source cache on playback source change");
        }
        currentSourceCacheKeys.clear();
    }

    private void clearCachedResource(@NonNull String cacheKey, @NonNull String reason) {
        if (!isAlreadyCached(cacheKey)) {
            return;
        }
        try {
            cache.removeResource(cacheKey);
            currentSourceCacheKeys.remove(cacheKey);
            Log.d(TAG, "Removed cached segment: " + compactSegmentLabel(cacheKey)
                    + ", reason=" + reason);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to remove cached segment: " + cacheKey + ", reason=" + reason, e);
        }
    }

    private boolean isAlreadyCached(@NonNull String cacheKey) {
        return !cache.getCachedSpans(cacheKey).isEmpty();
    }

    private static boolean isTsSegmentUri(@NonNull Uri uri) {
        String value = uri.toString().toLowerCase();
        return value.endsWith(".ts") || value.contains(".ts?");
    }

    @NonNull
    private static String buildCacheKey(@NonNull Uri uri) {
        return uri.toString();
    }

    @NonNull
    private static String compactSegmentLabel(@NonNull String cacheKey) {
        int slashIndex = cacheKey.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < cacheKey.length()) {
            return cacheKey.substring(slashIndex + 1);
        }
        return cacheKey;
    }

    private int getLiveWindowSize() {
        synchronized (liveWindowLock) {
            return currentLiveWindowCacheKeys.size();
        }
    }

    @NonNull
    private static String summarizeUris(@NonNull List<Uri> uris) {
        List<String> labels = new ArrayList<>(uris.size());
        for (Uri uri : uris) {
            labels.add(compactSegmentLabel(buildCacheKey(uri)));
        }
        return summarizeCacheKeys(labels);
    }

    @NonNull
    private static String summarizeCacheKeys(@NonNull List<String> cacheKeys) {
        if (cacheKeys.isEmpty()) {
            return "[]";
        }
        List<String> labels = new ArrayList<>(cacheKeys.size());
        for (String cacheKey : cacheKeys) {
            labels.add(compactSegmentLabel(cacheKey));
        }
        return labels.toString();
    }

    private void logWorkerState(@NonNull String prefix) {
        List<String> states = new ArrayList<>(workerSlots.length);
        for (WorkerSlot workerSlot : workerSlots) {
            states.add(workerSlot.describeState());
        }
        Log.d(TAG, prefix + ": " + states);
    }

    @NonNull
    private DataSource createPlaybackCacheDataSource() {
        CacheDataSource cacheDataSource = new CacheDataSource(
                cache,
                upstreamFactory.createDataSource(),
                new FileDataSource(),
                new CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE),
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
                new CacheDataSource.EventListener() {
                    @Override
                    public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
                        if (cachedBytesRead > 0) {
                            Log.d(TAG, "Playback cache hit: read " + cachedBytesRead
                                    + " byte(s) from cache, cacheSize=" + cacheSizeBytes);
                        }
                    }

                    @Override
                    public void onCacheIgnored(int reason) {
                        Log.d(TAG, "Playback cache ignored, reason=" + cacheIgnoredReasonName(reason));
                    }
                },
                cacheKeyFactory);
        return new LoggingPlaybackDataSource(cacheDataSource, upstreamFactory.createDataSource());
    }

    @NonNull
    private static String cacheIgnoredReasonName(int reason) {
        switch (reason) {
            case CacheDataSource.CACHE_IGNORED_REASON_ERROR:
                return "CACHE_ERROR";
            case CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH:
                return "UNSET_LENGTH";
            default:
                return "UNKNOWN(" + reason + ")";
        }
    }

    public void release() {
        resetLiveWindowTracking();
        cancelAllPrefetchTasks();
        clearCurrentSourceCache();
        for (WorkerSlot workerSlot : workerSlots) {
            workerSlot.release();
        }
    }

    private final class LoggingPlaybackDataSource implements DataSource {

        private final CacheDataSource cacheDelegate;
        private final DataSource upstreamDelegate;
        private DataSource activeDelegate;

        private LoggingPlaybackDataSource(@NonNull CacheDataSource cacheDelegate,
                                          @NonNull DataSource upstreamDelegate) {
            this.cacheDelegate = cacheDelegate;
            this.upstreamDelegate = upstreamDelegate;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            cacheDelegate.addTransferListener(transferListener);
            upstreamDelegate.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            close();
            String cacheKey = buildCacheKey(dataSpec.uri);
            boolean bypassCache = false;
            if (!cacheKey.isEmpty() && isTsSegmentUri(dataSpec.uri)) {
                bypassCache = onPlaybackSegmentRequested(dataSpec.uri);
            }
            if (bypassCache) {
                Log.d(TAG, "Playback open via upstream only: " + dataSpec.uri);
                activeDelegate = upstreamDelegate;
                return activeDelegate.open(dataSpec);
            }
            if (!cacheKey.isEmpty() && isAlreadyCached(cacheKey)) {
                Log.d(TAG, "Playback open with cached span ready: " + dataSpec.uri);
            } else {
                Log.d(TAG, "Playback open without cache hit: " + dataSpec.uri);
            }
            activeDelegate = cacheDelegate;
            return activeDelegate.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return activeDelegate != null
                    ? activeDelegate.read(buffer, offset, readLength)
                    : C.RESULT_END_OF_INPUT;
        }

        @Override
        public Uri getUri() {
            return activeDelegate != null ? activeDelegate.getUri() : null;
        }

        @Override
        @NonNull
        public Map<String, List<String>> getResponseHeaders() {
            return activeDelegate != null
                    ? activeDelegate.getResponseHeaders()
                    : Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            if (activeDelegate != null) {
                activeDelegate.close();
                activeDelegate = null;
            }
        }
    }

    private static final class WorkerAssignment {
        private final long version;
        @NonNull
        private final String cacheKey;
        private final Uri segmentUri;
        private final int generation;
        @NonNull
        private final WorkerState state;

        private WorkerAssignment(long version,
                                 @NonNull String cacheKey,
                                 Uri segmentUri,
                                 int generation,
                                 @NonNull WorkerState state) {
            this.version = version;
            this.cacheKey = cacheKey;
            this.segmentUri = segmentUri;
            this.generation = generation;
            this.state = state;
        }

        private boolean isSameGeneration(int generation) {
            return this.generation == generation;
        }
    }

    private final class WorkerSlot implements Runnable {
        private final int slotIndex;
        private final Object lock = new Object();
        private final Thread thread;
        /** 每次重新分配或清空任务时递增，用于识别过期 assignment。 */
        private long version;
        /** release 后退出 worker 循环。 */
        private boolean released;
        /** 当前槽位目标分片的 cache key；空串表示当前没有目标。 */
        private String assignedCacheKey = "";
        /** 当前槽位目标分片的 URI。 */
        private Uri assignedSegmentUri;
        /** 当前槽位目标对应的播放源 generation。 */
        private int assignedGeneration = -1;
        /** 当前槽位状态：空闲、已分配、执行中、已完成。 */
        private WorkerState state = WorkerState.IDLE;
        /** 正在执行下载的 cache key，仅用于日志与抢占取消判断。 */
        private volatile String executingCacheKey = "";
        /** 正在执行下载对应的 generation。 */
        private volatile int executingGeneration = -1;

        private WorkerSlot(int slotIndex) {
            this.slotIndex = slotIndex;
            this.thread = new Thread(this, "hls-prefetch-worker-" + slotIndex);
        }

        private void start() {
            thread.start();
        }

        private void assign(@NonNull Uri segmentUri, int generation) {
            String cacheKey = buildCacheKey(segmentUri);
            boolean shouldInterrupt = false;
            synchronized (lock) {
                if (!released
                        && generation == assignedGeneration
                        && cacheKey.equals(assignedCacheKey)
                        && segmentUri.equals(assignedSegmentUri)) {
                    return;
                }
                version++;
                assignedCacheKey = cacheKey;
                assignedSegmentUri = segmentUri;
                assignedGeneration = generation;
                state = WorkerState.ASSIGNED;
                shouldInterrupt = isExecutingLocked();
                lock.notifyAll();
            }
            if (shouldInterrupt) {
                thread.interrupt();
            }
            Log.d(TAG, "Assign worker[" + slotIndex + "] -> " + compactSegmentLabel(cacheKey));
        }

        private void clear(@NonNull String reason) {
            String previousCacheKey;
            boolean shouldInterrupt = false;
            synchronized (lock) {
                previousCacheKey = assignedCacheKey;
                if (released && previousCacheKey.isEmpty() && assignedSegmentUri == null) {
                    return;
                }
                version++;
                assignedCacheKey = "";
                assignedSegmentUri = null;
                assignedGeneration = -1;
                state = WorkerState.IDLE;
                shouldInterrupt = isExecutingLocked();
                lock.notifyAll();
            }
            if (shouldInterrupt) {
                thread.interrupt();
            }
            if (!previousCacheKey.isEmpty()) {
                Log.d(TAG, "Clear worker[" + slotIndex + "] target="
                        + compactSegmentLabel(previousCacheKey) + ", reason=" + reason);
            }
        }

        private boolean cancelIfHandling(@NonNull String cacheKey, @NonNull String reason) {
            boolean handled;
            synchronized (lock) {
                handled = (state == WorkerState.ASSIGNED || state == WorkerState.EXECUTING)
                        && (cacheKey.equals(assignedCacheKey) || cacheKey.equals(executingCacheKey));
                if (!handled) {
                    return false;
                }
                version++;
                if (cacheKey.equals(assignedCacheKey)) {
                    assignedCacheKey = "";
                    assignedSegmentUri = null;
                    assignedGeneration = -1;
                }
                state = WorkerState.IDLE;
                lock.notifyAll();
            }
            thread.interrupt();
            Log.d(TAG, "Cancel worker[" + slotIndex + "] target="
                    + compactSegmentLabel(cacheKey) + ", reason=" + reason);
            return true;
        }

        @NonNull
        private WorkerAssignment snapshot() {
            synchronized (lock) {
                return new WorkerAssignment(
                        version,
                        assignedCacheKey,
                        assignedSegmentUri,
                        assignedGeneration,
                        state);
            }
        }

        private boolean isStillAssigned(@NonNull WorkerAssignment assignment) {
            synchronized (lock) {
                return !released
                        && version == assignment.version
                        && assignment.generation == assignedGeneration
                        && assignment.cacheKey.equals(assignedCacheKey)
                        && assignment.segmentUri == assignedSegmentUri
                        && state != WorkerState.IDLE;
            }
        }

        private void markExecuting(@NonNull String cacheKey, int generation) {
            synchronized (lock) {
                if (cacheKey.equals(assignedCacheKey) && generation == assignedGeneration) {
                    state = WorkerState.EXECUTING;
                }
            }
            executingCacheKey = cacheKey;
            executingGeneration = generation;
        }

        private void markCompleted(@NonNull String cacheKey, int generation) {
            synchronized (lock) {
                if (cacheKey.equals(assignedCacheKey) && generation == assignedGeneration) {
                    state = WorkerState.COMPLETED;
                }
            }
        }

        private void clearExecuting(@NonNull String cacheKey, int generation) {
            if (cacheKey.equals(executingCacheKey) && generation == executingGeneration) {
                executingCacheKey = "";
                executingGeneration = -1;
            }
        }

        private void release() {
            synchronized (lock) {
                released = true;
                version++;
                assignedCacheKey = "";
                assignedSegmentUri = null;
                assignedGeneration = -1;
                state = WorkerState.IDLE;
                lock.notifyAll();
            }
            thread.interrupt();
        }

        @Override
        public void run() {
            long observedVersion = -1L;
            while (true) {
                WorkerAssignment assignment = awaitAssignment(observedVersion);
                if (assignment == null) {
                    return;
                }
                observedVersion = assignment.version;
                if (assignment.segmentUri == null || assignment.cacheKey.isEmpty()) {
                    continue;
                }
                runPrefetch(this, assignment);
            }
        }

        private WorkerAssignment awaitAssignment(long observedVersion) {
            synchronized (lock) {
                while (!released && version == observedVersion) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                        if (released) {
                            return null;
                        }
                    }
                }
                if (released) {
                    return null;
                }
                return new WorkerAssignment(
                        version,
                        assignedCacheKey,
                        assignedSegmentUri,
                        assignedGeneration,
                        state);
            }
        }

        private boolean isExecutingLocked() {
            return executingCacheKey != null && !executingCacheKey.isEmpty();
        }

        @NonNull
        private String describeState() {
            synchronized (lock) {
                String target = assignedCacheKey.isEmpty()
                        ? "-"
                        : compactSegmentLabel(assignedCacheKey);
                String executing = executingCacheKey == null || executingCacheKey.isEmpty()
                        ? "-"
                        : compactSegmentLabel(executingCacheKey);
                return "w" + slotIndex + "{state=" + state.name().toLowerCase()
                        + ", target=" + target + ", executing=" + executing + "}";
            }
        }
    }
}
