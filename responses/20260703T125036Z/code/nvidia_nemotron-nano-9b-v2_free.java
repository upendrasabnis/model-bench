<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260703T125036Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->


To implement a thread-safe concurrent LRU cache in Java with O(1) average operations and correct eviction under concurrent access, we use a combination of a doubly linked list for tracking usage order and a `HashMap` for O(1) lookups. The thread-safety is ensured by a `ReentrantLock` that synchronizes access to the critical sections of the code. Below is the implementation and JUnit 5 tests, including a stress test.

---

### **Implementation**

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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
                    Node<K, V> evicted = removeTail();
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
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void addToHead(Node<K, V> node) {
        node.next = head;
        node.prev = null;
        if (head != null) head.prev = node;
        head = node;
        if (tail == null) tail = head;
    }

    private Node<K, V> removeTail() {
        Node<K, V> node = tail;
        removeNode(node);
        tail = node.prev;
        if (tail == null) head = null;
        return node;
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

---

### **Thread-Safety Explanation**

- **Locking Mechanism**: A `ReentrantLock` is used to synchronize access to the `map` and the linked list. All operations (get/put) acquire the lock before modifying the data structures, ensuring atomicity and preventing race conditions.
- **Atomic Eviction**: When the cache reaches capacity, the eviction of the least recently used (LRU) item is done within the lock, ensuring that no other thread interferes during the removal.
- **O(1) Operations**: The `HashMap` provides O(1) average time for lookups, and the linked list operations (add/remove) are O(1) when properly synchronized.

---

### **JUnit 5 Tests**

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

public class LRUCacheTest {

    @Test
    public void testGetAndPut() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        cache.put(3, "three"); // Evicts 1
        assertNull(cache.get(1));
    }

    @Test
    public void testEviction() {
        LRUCache<Integer, String> cache = new LRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
    }

    @Timeout(value = 1