<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->


To implement a thread-safe concurrent LRU cache in Java with O(1) average operations and correct eviction under concurrent access, we use a combination of a `HashMap` for O(1) key lookups and a doubly linked list to maintain the order of usage. All critical operations are protected by a single lock to ensure thread safety.

### Key Components
1. **Node Class**: Represents each entry in the cache with key, value, and pointers to previous/next nodes.
2. **LRUCache Class**: 
   - Uses a `HashMap` to map keys to nodes.
   - Maintains a doubly linked list to track usage order.
   - All operations (`get`, `put`, `removeNode`, `addToFront`) are synchronized using a lock to prevent race conditions.

### Thread Safety
- **Locking Mechanism**: The `synchronized` keyword ensures that only one thread can execute critical sections (e.g., modifying the linked list or map) at a time. This prevents concurrent modifications that could lead to inconsistent states.

### JUnit 5 Tests
- **Unit Tests**: Verify basic `get` and `put` operations, including eviction logic.
- **Stress Test**: Simulates concurrent access with multiple threads performing random `put` and `get` operations. After completion, it checks that the cache size does not exceed capacity.

### Code Implementation

```java
// Node class
public class Node {
    private final String key;
    private Object value;
    private Node prev;
    private Node next;

    public Node(String key, Object value) {
        this.key = key;
        this.value = value;
    }

    // Getters and setters if needed
}

// LRUCache class
public class LRUCache {
    private final int capacity;
    private final Map<String, Node> map;
    private Node head;
    private Node tail;
    private final Object lock = new Object();

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
    }

    public Object get(String key) {
        synchronized (lock) {
            Node node = map.get(key);
            if (node == null) return null;
            removeNode(node);
            addToFront(node);
            return node.value;
        }
    }

    public void put(String key, Object value) {
        synchronized (lock) {
            Node node = map.get(key);
            if (node != null) {
                node.value = value;
                removeNode(node);
                addToFront(node);
            } else {
                if (map.size() >= capacity) {
                    Node lru = tail;
                    removeNode(lru);
                    map.remove(lru.key);
                }
                Node newNode = new Node(key, value);
                addToFront(newNode);
                map.put(key, newNode);
            }
        }
    }

    private void removeNode(Node node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
    }

    private void addToFront(Node node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        } else {
            tail = node;
        }
        head = node;
    }

    public int size() {
        return map.size();
    }
}
```

### JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

public class LRUCacheTest {

    @Test
    void testGet() {
        LRUCache cache = new LRUCache(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, (int) cache.get("a"));
        assertEquals(2, (int) cache.get("b"));
        cache.put("c", 3); // Evicts "a"
        assertNull(cache.get("a"));
        assertEquals(3, (int) cache.get("c