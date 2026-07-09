<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260709T082632Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->

I'll implement a thread-safe concurrent LRU cache in Java using a combination of `ReentrantReadWriteLock` and a doubly-linked list with HashMap for O(1) operations.
<tool_call>file
<arg_key>path</arg_key>
<arg_value>/lru-cache/src/main/java/com/example/cache/LRUCache.java</arg_value>
<arg_key>content</arg_key>
<arg_value>package com.example.cache;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe LRU Cache implementation with O(1) average operations.
 * Uses a combination of HashMap and doubly-linked list.
 */
public class LRUCache<K, V> {
    private final int capacity;
    private final MapWithLock<K, V> map;
    
    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new MapWithLock<>(capacity);
    }
    
    /**
     * Gets the value for the given key, moving it to the front if found.
     * @param key the key to look up
     * @return the value associated with the key, or null if not found
     */
    public V get(K key) {
        if (key == null) return null;
        
        map.readLock().lock();
        try {
            Node<K, V> node = map.getNode(key);
            if (node == null) {
                return null;
            }
            // Must upgrade to write lock to move node to front
            map.readLock().unlock();
            map.writeLock().lock();
            try {
                // Re-check after lock upgrade (another thread might have modified)
                node = map.getNodeUnsafe(key);
                if (node != null) {
                    map.moveToFront(node);
                }
                return node != null ? node.value : null;
            } finally {
                map.writeLock().unlock();
            }
        } finally {
            if (map.readLock().isHeldByCurrentThread()) {
                map.readLock().unlock();
            }
        }
    }
    
    /**
     * Puts a key-value pair into the cache, evicting the LRU entry if necessary.
     * @param key the key to put
     * @param value the value to associate with the key
     * @return the previous value if the key existed, null otherwise
     */
    public V put(K key, V value) {
        if (key == null) return null;
        
        map.writeLock().lock();
        try {
            Node<K, V> existingNode = map.getNodeUnsafe(key);
            V oldValue = null;
            
            if (existingNode != null) {
                oldValue = existingNode.value;
                existingNode.value = value;
                map.moveToFront(existingNode);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                map.addNode(newNode);
                
                if (map.size() > capacity) {
                    map.removeLRU();
                }
            }
            return oldValue;
        } finally {
            map.writeLock().unlock();
        }
    }
    
    /**
     * Returns the current size of the cache.
     */
    public int size() {
        map.readLock().lock();
        try {
            return map.size();
        } finally {
            map.readLock().unlock();
        }
    }
    
    /**
     * Clears all entries from the cache.
     */
    public void clear() {
        map.writeLock().lock();
        try {
            map.clear();
        } finally {
            map.writeLock().unlock();
        }
    }
    
    // Internal node class for doubly-linked list
    static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
        
        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
    
    // Internal class managing the HashMap and linked list with locking
    static class MapWithLock<K, V> {
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final java.util.Map<K, Node<K, V>> map;
        private final Node<K, V> head; // Most recently used
        private Node<K, V> tail;       // Least recently used
        
        MapWithLock(int capacity) {
            this.map = new java.util.HashMap<>(Math.max(capacity, 16), 0.75f);
            this.head = new Node<>(null, null);
            this.tail = head;
        }
        
        ReadWriteLock readWriteLock() {
            return lock;
        }
        
        java.util.concurrent.locks.Lock readLock() {
            return lock.readLock();
        }
        
        java.util.concurrent.locks.Lock writeLock() {
            return lock.writeLock();
        }
        
        Node<K, V> getNode(K key) {
            return map.get(key);
        }
        
        Node<K, V> getNodeUnsafe(K key) {
            return map.get(key);
        }
        
        void addNode(Node<K, V> node) {
            // Add to front (after head)
            node.prev = head;
            node.next = head.next;
            if (head.next != null) {
                head.next.prev = node;
            }
            head.next = node;
            
            if (tail == head) {
                tail = node;
            }
            
            map.put(node.key, node);
        }
        
        void moveToFront(Node<K, V> node) {
            if (node == head.next) {
                return; // Already at front
            }
            
            // Remove from current position
            if (node.prev != null) {
                node.prev.next = node.next;
            }
            if (node.next != null) {
                node.next.prev = node.prev;
            }
            
            // Update tail if needed
            if (node == tail) {
                tail = node.prev;
            }
            
            // Add to front
            node.prev = head;
            node.next = head.next;
            if (head.next != null) {
                head.next.prev = node;
            }
            head.next = node;
            
            if (tail == node) {
                tail = node.prev;
            }
        }
        
        void removeLRU() {
            Node<K, V> lru = tail;
            if (lru == head) {
                return; // Empty
            }
            
            // Remove from linked list
            if (lru.prev != null) {
                lru.prev.next = lru.next;
            }
            tail = lru.prev;
            
            // Remove from map
            map.remove(lru.key);
        }
        
        int size() {
            return map.size();
        }
        
        void clear() {
            map.clear();
            head.next = null;
            tail = head;
        }
    }
}</arg_value>
</tool_call>
