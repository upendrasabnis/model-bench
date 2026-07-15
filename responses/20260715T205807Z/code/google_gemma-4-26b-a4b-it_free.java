<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260715T205807Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ complexity, the standard approach is to combine a **HashMap** (for $O(1)$ lookups) with a **Doubly Linked List** (for $O(1)$ updates to the access order).

### Implementation

To ensure thread safety while maintaining high performance, we use `ReentrantLock`. While `Collections.synchronizedMap` is an option, a manual lock allows us to wrap the "check-then-act" logic (checking capacity and evicting) into a single atomic operation.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

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

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        
        // Dummy head and tail to avoid null checks during pointer manipulation
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node!= null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addNode(newNode);
                if (map.size() > capacity) {
                    evict();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    // --- Internal Helper Methods (Must be called under lock) ---

    private void addNode(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addNode(node);
    }

    private void evict() {
        Node<K, V> last = tail.prev;
        if (last!= head) {
            removeNode(last);
            map.remove(last.key);
        }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Atomicity of Composite Operations**: A common mistake is using `ConcurrentHashMap` for the map and then performing separate logic for the linked list. This leads to race conditions where the size exceeds capacity or the list becomes corrupted. I used a `ReentrantLock` to ensure that the **entire** operation (searching the map, updating the list, and potentially evicting) is a single atomic transaction.
2.  **Mutual Exclusion**: The lock ensures that only one thread can modify the pointers of the doubly linked list or the `HashMap` at a time, preventing "lost updates" or "corrupted pointers" during concurrent `put` operations.
3.  **Visibility**: The `ReentrantLock` provides the necessary *happens-before* relationship. Once a thread releases the lock, all changes made to the internal `map` and `Node` pointers are visible to the next thread that acquires the lock.

---

### JUnit 5 Tests

I have included a standard functional test and a high-concurrency stress test using a `CountDownLatch` to ensure all threads start simultaneously.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit