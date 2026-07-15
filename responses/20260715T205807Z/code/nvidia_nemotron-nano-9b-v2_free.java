<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260715T205807Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->


To implement a thread-safe concurrent LRU cache in Java with O(1) average operations and correct eviction under concurrent access, we use a combination of a `HashMap` for O(1) key lookups and a doubly linked list to maintain the order of usage. A `ReentrantLock` ensures thread-safety by serializing access to critical sections.

### **Implementation Details**

1. **Data Structures**:
   - **`HashMap<K, Node<K, V>>`**: Maps keys to nodes for O(1) access.
   - **Doubly Linked List**: Maintains the order of usage, with the most recently used (MRU) at the head and least recently used (LRU) at the tail.

2. **Thread-Safety**:
   - A `ReentrantLock` is used to synchronize all `get` and `put` operations. This ensures that only one thread can modify the cache at a time, preventing race conditions.

3. **Operations**:
   - **`get(K key)`**: 
     - Acquires the lock, checks the map, moves the node to the head (if found), and releases the lock.
   - **`put(K key, V value)`**:
     - Acquires the lock, updates or adds the node, moves it to the head, and evicts the LRU node if capacity is exceeded.

### **Code Implementation**

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
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock;
    private int size;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
        this.lock = new ReentrantLock();
        this.size = 0;
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
                node = new Node<>(key, value);
                map.put(key, node);
                addToHead(node);
                size++;
                if (size > capacity) {
                    Node<K, V> lru = tail.prev;
                    removeNode(lru);
                    map.remove(lru.key);
                    size--;
                }
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
}
```

### **JUnit 5 Tests**

#### **Basic Tests**
```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LRUCacheTest {
    @Test
    void testGetPut() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, cache.get("a"));
        assertEquals(2, cache.get("b"));
        cache.put("c", 3);
        assertNull(cache.get("a")); // Evicted
        assertEquals(3, cache.get("c"));
    }

    @Test
    void testEviction() {
        LRUCache<String, Integer> cache = new LRUCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c",