<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260808T173453Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe concurrent LRU cache in Java with O(1) average operations and correct eviction under concurrent access, we use a combination of a `HashMap` for O(1) lookups and a `DoublyLinkedList` to track usage order. A `ReentrantLock` ensures thread safety by serializing access to the data structures.

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

class DoublyLinkedList<K, V> {
    private Node<K, V> head;
    private Node<K, V> tail;

    public void addToHead(Node<K, V> node) {
        if (head == null) {
            head = tail = node;
        } else {
            node.next = head;
            head.prev = node;
            head = node;
        }
    }

    public void remove(Node<K, V> node) {
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

    public Node<K, V> removeTail() {
        Node<K, V> tailNode = tail;
        remove(tailNode);
        return tailNode;
    }
}

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final DoublyLinkedList<K, V> list;
    private final ReentrantLock lock;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.list = new DoublyLinkedList<>();
        this.lock = new ReentrantLock();
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            list.remove(node);
            list.addToHead(node);
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
                list.remove(node);
                list.addToHead(node);
            } else {
                if (map.size() >= capacity) {
                    Node<K, V> tail = list.removeTail();
                    map.remove(tail.key);
                }
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                list.addToHead(newNode);
            }
        } finally {
            lock.unlock();
        }
    }
}
```

### Explanation of Thread-Safety

- **ReentrantLock**: Ensures that only one thread can execute `get` or `put` at a time. This serializes access to the `map` and `list`, preventing race conditions.
- **Atomic Operations**: All modifications to the `map` and `list` (e.g., adding/removing nodes) are wrapped in the lock, guaranteeing consistency.
- **O(1) Operations**: The `HashMap` and `DoublyLinkedList` operations (add/remove) are O(1) on average, and the lock is held for a constant time, maintaining O(1) average time complexity.

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Test
void testGetNonExistentKey() {
    ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
    assertNull(cache.get("key1"));
}

@Test
void testPutAndGet() {
    ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
    cache.put("key1", 1);
    assertEquals(1, cache.get("key1"));
    cache.put("key2", 2);
    assertEquals(2, cache.get("key2"));
    assertNull(cache.get("key3"));
}

@Test
void testEviction() {
    ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
    cache.put("key1", 1);
    cache.put("key2", 2);
    cache.put("key3", 3);
    assertNull(cache.get("key1"));
    assertEquals(3, cache.get("key3"));
    assertEquals(2, cache.get("key2"));
}

@Test
void testUpdateKey() {
    ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
    cache.put("key1", 1);
    cache.put("key2", 2);
    cache.put("key1", 3);
    assertEquals(3, cache.get("key1"));
    assertEquals(2, cache.get("key2"));
}

@Test
@Repeat(100)
void testConcurrentAccess() throws InterruptedException {
    ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(10);
    int numThreads = 100;
    int operationsPerThread = 1000;

    CountDownLatch latch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
        Thread thread = new Thread(() -> {
            try {
                for (int j = 0; j < operationsPerThread; j++) {
                    String key = "key" + j;
                    cache.put(key, j);
                    cache.get(key); // Update LRU
                }
            } finally {
                latch.countDown();
            }
        });
        thread.start();
    }

    latch.await();

    assertEquals(10,