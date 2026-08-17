<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260817T083627Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->


To implement a thread-safe concurrent LRU cache in Java, we use a combination of a `HashMap` for O(1) key lookups and a doubly linked list to maintain the order of recently used items. Thread safety is ensured using a `ReentrantLock` to synchronize access to the critical sections of the code. Below is the implementation and JUnit 5 tests, including a stress test.

### Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

    public LRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.head = new Node<>();
        this.tail = new Node<>();
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            // Move to head
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
                    // Evict least recently used
                    Node<K, V> evicted = removeTail();
                    map.remove(evicted.key);
                }
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private Node<K, V> removeTail() {
        Node<K, V> node = tail.prev;
        removeNode(node);
        return node;
    }

    private int size() {
        return map.size();
    }

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node() {}

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
```

### Explanation of Thread-Safety

- **Single Lock for Critical Sections**: A `ReentrantLock` is used to ensure that only one thread can execute `get` or `put` operations at a time. This prevents race conditions when modifying the linked list or the hash map.
- **Atomic Operations**: All modifications to the cache (e.g., moving nodes in the linked list, updating the map) are wrapped within the lock's scope, ensuring atomicity.
- **Correct Eviction**: When the cache is full, the lock ensures that eviction (removing the least recently used item) is performed atomically, even under concurrent access.

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LRUCacheTest {

    @Test
    @DisplayName("1. Test get on empty cache")
    void testGetEmpty() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        assertNull(cache.get(1));
    }

    @Test
    @DisplayName("2. Test put and get")
    void testPutGet() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
    }

    @Test
    @DisplayName("3. Test eviction")
    void testEviction() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("two", cache.get(2));
        cache.put(3, "three");
        assertNull(cache.get(1)); // 1 was evicted
    }

    @Test
    @DisplayName("4. Test update existing key")
    void testUpdate() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(1, "updated");
        assertEquals("updated", cache.get(1));
    }

    @Test
    @