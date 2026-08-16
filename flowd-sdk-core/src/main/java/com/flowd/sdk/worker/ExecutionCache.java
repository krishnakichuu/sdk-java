package com.flowd.sdk.worker;

import com.flowd.sdk.internal.replayer.Execution;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded LRU cache of in-flight workflow Executions, keyed by run — the
 * mechanism behind sticky worker caching: keeping a live Execution (with
 * parked coroutines) in memory between tasks so only new events need
 * processing, instead of a full history replay on every task. Mirrors
 * worker/cache.go's executionCache (Go) exactly, including its concurrency
 * assumption: only ever touched from the single virtual thread that
 * processes workflow tasks (FlowdWorker polls workflow tasks one at a time,
 * sequentially), so it needs no locking of its own.
 *
 * <p>Eviction past capacity just drops the reference — the evicted
 * Execution's coroutines, if the workflow hasn't reached a terminal
 * outcome, stay parked in {@code yield()} forever (a real, but small and
 * capacity-bounded, leak — see {@code Coroutine.yield}'s doc). Accepted
 * deliberately rather than adding cooperative cancellation, which would put
 * the determinism guarantee at risk for a memory optimization.
 */
final class ExecutionCache {
    record Key(String workflowId, String runId) {
    }

    record CachedExecution(Execution exec, String workflowType, long lastEventId) {
    }

    private final int capacity;
    private final LinkedHashMap<Key, CachedExecution> lru;

    ExecutionCache(int capacity) {
        this.capacity = capacity;
        this.lru = new LinkedHashMap<>(16, 0.75f, /* accessOrder= */ true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, CachedExecution> eldest) {
                return size() > ExecutionCache.this.capacity;
            }
        };
    }

    CachedExecution get(Key key) {
        return lru.get(key); // LinkedHashMap(accessOrder=true).get already moves-to-front
    }

    void put(Key key, CachedExecution value) {
        lru.put(key, value);
    }

    void delete(Key key) {
        lru.remove(key);
    }

    int size() {
        return lru.size();
    }
}
