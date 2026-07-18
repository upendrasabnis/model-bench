<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
## 1.  Design Overview

* **Goal:**  A concurrent LRU cache that supports  
  * `get(K key)` – return the value or `null` – **O(1)** average time.  
  * `put(K key, V value)` – insert / replace – **O(1)** average time.  
  * Evicts the *least‑recently‑used* entry when the configured capacity is exceeded.  
  * Works correctly under heavy parallel load.

* **Key idea** – We keep two data structures that are kept in sync:  

  1. **`ConcurrentHashMap<K, Node>`** – fast key‑to‑node lookup.  
  2. **Doubly‑linked list of `Node`** – keeps the access order (head = most recent, tail = least recent).

  All operations that change the *ordering* (move‑to‑head, insertion, eviction) are protected by a single `ReentrantLock`.  
  The map itself is a concurrent map, so look‑ups (`map.get`) can happen without locking – only the *list* part needs the lock.  
  Because we lock *before* we touch the map **and** the list, the two structures stay in sync and the cache is thread‑safe.

* **Why it is O(1)**  
  * `ConcurrentHashMap.get/put` is O(1) average.  
  * List operations (`remove`, `insert`, `evict`) touch a constant number of pointers – O(1).  
  * The lock only protects a handful of pointer assignments, so the overhead is negligible compared to the map lookup.

* **Thread‑safety guarantees**  

  * **Mutual exclusion** – The `ReentrantLock` guarantees that only one thread at a time can modify the ordering list or perform an eviction.  
  * **Visibility** – All writes inside the locked block are flushed to main memory before the lock is released, so other threads will see the updated state immediately.  
  * **Map/List consistency** – Because the map entry is inserted *inside* the locked block, a thread that reads the map will never see a node that is not yet linked into the list (or vice‑versa).  
  * **No deadlock** – The lock is always acquired in the same order (first the lock, then the map). There are no nested locks or other resources that could lead to a cycle.

---

## 2.  Implementation

```java
package com.example.lru;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with a fixed capacity.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ConcurrentLRUCache<K, V> {

    /* ------------------------------------------------------------ *
     *  Node – a doubly linked list element                         *
     * ------------------------------------------------------------ */
    private static final class Node<K, V> {
        final K key;
        volatile V value;          // volatile to guarantee visibility
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /* ------------------------------------------------------------ *
     *  Instance fields                                               *
     * ------------------------------------------------------------ */
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map = new ConcurrentHashMap<>();

    /* Dummy head/tail nodes – simplify insert / remove logic */
    private final Node<K, V> head = new Node<>(null, null);  // most recent
    private final Node<K, V> tail = new Node<>(null, null);  // least recent
    private final ReentrantLock lock = new ReentrantLock();

    /* ------------------------------------------------------------ *
     *  Public API                                                     *
     * ------------------------------------------------------------ */

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if none.
     * Marks the entry as most recently used.
     */
    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        // Move to head under lock
        lock.lock();
        try {
            moveToHead(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /**
     * Inserts or replaces the value for {@code key}.  Marks the entry as
     * most recently used and evicts the LRU entry if capacity is exceeded.
     */
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                // Update value and move to head
                node.value = value;
                moveToHead(node);
            } else {
                // New node
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
                if (map.size() > capacity) {
                    evictTail();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of entries.
     */
    public int size() {
        return map.size();
    }

    /**
     * Returns a snapshot of all keys in the cache.  The order is not
     * guaranteed.  Used only for tests.
     */
    public java.util.Set<K> keySet() {
        return map.keySet();
    }

    /* ------------------------------------------------------------ *
     *  Internal list manipulation (always called while holding lock) *
     * ------------------------------------------------------------ */

    private void moveToHead(Node<K, V> node) {
        if (node == head.next) {
            return; // already head
        }
        // unlink
        unlink(node);
        // insert at head
        addToHead(node);
    }

    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
