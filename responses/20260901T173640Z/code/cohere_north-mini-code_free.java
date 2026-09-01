<!-- model: cohere/north-mini-code:free | category: code | run: 20260901T173640Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe concurrent LRU cache in Java with configurable capacity, O(1) average operations, and correct eviction under concurrent access, we can use a combination of a HashMap for O(1) lookups and a doubly linked list to maintain the order of elements. The thread-safety is ensured by synchronizing all methods that access or modify the cache's internal state.

## Implementation

Here's the implementation of the `ConcurrentLRUCache` class:

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cacheMap;
    private final Node<K, V> head; // dummy head for easier operations
    private final Node<K, V> tail; // dummy tail
    
    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }
    
    public V get(K key) {
        synchronized (this) {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
            // Move node to front
            moveToHead(node);
            return node.value;
        }
    }
    
    public void put(K key, V value) {
        synchronized (this) {
            Node<K, V> node = cacheMap.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                cacheMap.put(key, newNode);
                addToHead(newNode);
                if (cacheMap.size() > capacity) {
                    Node<K, V> lru = removeTail();
                    cacheMap.remove(lru.key);
                }
            }
        }
    }
    
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }
    
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
    
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }
    
    private Node<K, V> removeTail() {
        Node<K, V> node = tail.prev;
        removeNode(node);
        return node;
    }
    
    public int size() {
        synchronized (this) {
            return cacheMap.size();
        }
    }
    
    public Set<K> keySet() {
        synchronized (this) {
            return new HashSet<>(cacheMap.keySet());
        }
    }
    
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
        
        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
```

## Thread-Safety Explanation

The thread-safety of the `ConcurrentLRUCache` is guaranteed by the following mechanisms:

1. **Synchronized Methods**: All public methods that access or modify the cache's internal state (`get`, `put`, `size`, `keySet`) are synchronized. This ensures that only one thread can execute these methods at a time, preventing race conditions.

2. **Atomic Operations**: The internal operations (like moving nodes, adding to head, removing from tail) are also synchronized, ensuring that the doubly linked list remains consistent even under concurrent access.

3. **Consistent State**: By synchronizing all operations, we ensure that the cache's state (the HashMap and the doubly linked list) remains consistent, even when multiple threads are performing get and put operations simultaneously.

## JUnit 5 Tests

Here are the JUnit 5 tests to verify the correctness and thread-safety of the `ConcurrentLRUCache`:

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentLRUCacheTest {
    @Test
    public void testGetPut() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
    }
    
    @Test
    public void testCapacityEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three"); // should evict 1
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
    }
    
    @Test
    public void testRecentlyAccessed() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.get(1); // 1 becomes most recently used
        cache.put(3, "three"); // should evict 2
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
    }
    
    @Test
    public void testThreadSafety() throws InterruptedException {
        int capacity = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        int numThreads = 10;
        int operationsPerThread = 10000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    int key = (threadId * operationsPerThread + j) % (capacity * 2); // Ensure some evictions
                    int value = threadId * operationsPerThread + j;
                    cache.put(key, value);
                    Integer retrieved = cache.get(key);
                    if (retrieved != null && retrieved == value) {
                        successCount.incrementAndGet();
                    }
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        // Verify cache size
        assertEquals(capacity, cache.size());
        
        // Verify that all entries are valid
        for (int key : cache.keySet()) {
            Integer value = cache.get(key);
            assertNotNull(value);
        }
    }
}
```

## Explanation of the Tests

1. **testGetPut**: Verifies basic get and put operations.
2. **testCapacityEviction**: Ensures that when the cache is full, the least recently used item is evicted.
3. **testRecentlyAccessed**: Checks that accessing an item via get() moves it to the most recently used position.
4. **testThreadSafety**: Performs a concurrency stress test where multiple threads perform a large number of get and put operations. It verifies that the cache's size remains consistent and that all entries are valid after all operations are completed.

This implementation and the corresponding tests ensure that the `ConcurrentLRU