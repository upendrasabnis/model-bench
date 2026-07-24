<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260724T121031Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe concurrent LRU cache in Java with O(1) average operations and correct eviction under concurrent access, we use a combination of a `HashMap` for O(1) lookups and a doubly linked list to maintain the order of usage. A `ReentrantLock` ensures thread safety by synchronizing all critical sections.

### Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

class Node<K, V> {
    K key;
    V value;
    Node<K, V> prev;
    Node<K, V> next;

    Node(K key, V value) {
        this.key = key;
        this.value = value;
    }
}

public class LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private Node<K, V> head;
    private Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            removeNode(node);
            addToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                removeNode(node);
                addToHead(node);
            } else {
                if (map.size() >= capacity) {
                    Node<K, V> evicted = tail;
                    removeNode(evicted);
                    map.remove(evicted.key);
                }
                Node<K, V> newNode = new Node<>(key, value);
                addToHead(newNode);
                map.put(key, newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    private void removeNode(Node<K, V> node) {
        if (node.prev != null) node.prev.next = node.next;
        else head = node.next;
        if (node.next != null) node.next.prev = node.prev;
        else tail = node.prev;
    }

    private void addToHead(Node<K, V> node) {
        node.next = head;
        node.prev = null;
        if (head != null) head.prev = node;
        head = node;
        if (tail == null) tail = head;
    }
}
```

### Thread-Safety Explanation

- **Locking Mechanism**: A `ReentrantLock` is used to synchronize all `get` and `put` operations. This ensures that only one thread can modify the cache's state (e.g., updating the linked list or hash map) at a time, preventing race conditions.
- **Atomic Operations**: All modifications to the linked list (e.g., moving a node to the head or removing a node) are performed within the locked section, guaranteeing atomicity.
- **Consistency**: By holding the lock during the entire operation, the cache's internal state remains consistent even under high concurrency.

### JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LRUCacheTest {

    @Test
    @DisplayName("Basic get and put operations")
    public void testBasicOperations() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        cache.put(3, "three");
        assertNull(cache.get(1));
        assertEquals("three", cache.get(3));
    }

    @Test
    @DisplayName("Eviction when capacity is reached")
