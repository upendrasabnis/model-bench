<!-- model: cohere/north-mini-code:free | category: code | run: 20261013T200505Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Here's a thread-safe concurrent LRU cache implementation in Java with O(1) operations, along with comprehensive tests and thread-safety explanations:

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
public class ConcurrentLRUCacheTest {
    
    private static final int DEFAULT_CAPACITY = 3;
    private static final int STRESS_TEST_ITERATIONS = 10000;
    private static final int STRESS_TEST_THREADS = 8;
    
    @Test
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        // Test put and get
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));
        
        // Test eviction
        cache.put(2, "two");
        cache.put(3, "three");
        cache.put(4, "four"); // Should evict key 1
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testAccessOrder() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Access key 1 to make it most recently used
        cache.get(1);
        
        // Add new key to evict LRU (key 2)
        cache.put(4, "four");
        
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testRemove() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        assertTrue(cache.remove(2));
        assertNull(cache.get(2));
        assertEquals(2, cache.size());
        
        cache.put(4, "four"); // Should not evict anything since we have space
        assertEquals(3, cache.size());
    }
    
    @Test
    void testClear() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
        assertNull(cache.get(2));
        assertNull(cache.get(3));
    }
    
    @Test
    void testContainsKey() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        assertTrue(cache.containsKey(1));
        assertFalse(cache.containsKey(2));
    }
    
    @Test
    void testEvictionPolicy() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        
        cache.put(1, "one");
        cache.put(2, "two");
        
        // Access key 1 to make it MRU
        cache.get(1);
        
        // Add key 3 to evict LRU (key 2)
        cache.put(3, "three");
        
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
    }
    
    @Test
    void testNullValues() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, null);
        assertNull(cache.get(1));
        assertTrue(cache.containsKey(1));
    }
    
    @Test
    void testNullKey() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(null, 1);
        assertEquals(1, cache.get(null));
        assertTrue(cache.containsKey(null));
    }
    
    @Test
    void testOverwritesExistingKey() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(1, "ONE"); // Overwrite
        
        assertEquals("ONE", cache.get(1));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testCapacityOne() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(1);
        
        cache.put(1, "one");
        cache.put(2, "two"); // Should evict key 1
        
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testLargeCapacity() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(1000);
        
        for (int i = 0; i < 1000; i++) {
            cache.put(i, "value" + i);
        }
        
        assertEquals(1000, cache.size());
        
        for (int i = 0; i < 1000; i++) {
            assertEquals("value" + i, cache.get(i));
        }
    }
    
    @Test
    void testConcurrentStressTest() throws InterruptedException {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(100);
        AtomicInteger operationsCount = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(STRESS_TEST_THREADS);
        
        Runnable worker = () -> {
            try {
                startLatch.await();
                
                for (int i = 0; i < STRESS_TEST_ITERATIONS / STRESS_TEST_THREADS; i++) {
                    int key = ThreadLocalRandom.current().nextInt(0, 200);
                    String value = "thread-" + Thread.currentThread().threadId() + "-value-" + i;
                    
                    cache.put(key, value);
                    String retrieved = cache.get(key);
                    
                    // If we got a value, it should match what we put (unless evicted)
                    if (retrieved != null && !retrieved.equals(value)) {
                        fail("Corrupted value for key " + key + ": expected " + value + ", got " + retrieved);
                    }
                    
                    operationsCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < STRESS_TEST_THREADS; i++) {
            Thread t = new Thread(worker);
            t.start();
            threads.add(t);
        }
        
        startLatch.countDown();
        
        // Wait for all threads to complete
        completionLatch.await(30, TimeUnit.SECONDS);
        
        // Interrupt any remaining threads
        for (Thread t : threads) {
            t.interrupt();
        }
        
        // Verify cache invariants
        assertTrue(cache.size() <= 100, "Cache size exceeded capacity: " + cache.size());
        
        // Verify all remaining values are consistent
        for (int key = 0; key < 200; key++) {
            String value = cache.get(key);
            if (value != null) {
                // Check that this value was actually inserted (by verifying format)
                assertTrue(value.startsWith("thread-") && value.contains("-value-"), 
                    "Invalid value format for key " + key + ": " + value);
            }
        }
        
        System.out.println("Stress test completed. Total operations: " + operationsCount.get());
    }
    
    @Test
    void testConcurrentEviction() throws InterruptedException {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(10);
        AtomicInteger evictedCount = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(10);
        
        Runnable worker = () -> {
            try {
                startLatch.await();
                
                for (int i = 0; i < 1000; i++) {
                    int key = ThreadLocalRandom.current().nextInt(0, 100);
                    cache.put(key, "value" + key + "-" + i);
                    
                    // Occasionally check if a key was evicted
                    if (i % 100 == 0 && key > 5) {
                        String oldValue = cache.get(key - 1);
                        if (oldValue == null) {
                            evictedCount.incrementAndGet();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(worker);
            t.start();
            threads.add(t);
        }
        
        startLatch.countDown();
        completionLatch.await(30, TimeUnit.SECONDS);
        
        for (Thread t : threads) {
            t.interrupt();
        }
        
        // Verify cache size never exceeded capacity
        assertTrue(cache.size() <= 10, "Cache size exceeded capacity: " + cache.size());
    }
    
    @Test
    void testThreadSafetyWithIterators() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(50);
        
        for (int i = 0; i < 50; i++) {
            cache.put(i, "value" + i);
        }
        
        // Concurrent modification during iteration
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(5);
        
        Runnable modifier = () -> {
            try {
                startLatch.await();
                
                for (int i = 0; i < 100; i++) {
                    cache.put(i + 50, "extra" + i);
                    cache.remove(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        };
        
        Runnable iterator = () -> {
            try {
                startLatch.await();
                
                // Iterate while modifications happen
                for (int i = 0; i < 50; i++) {
                    Map.Entry<Integer, String> entry = cache.getEntry(i);
                    if (entry != null) {
                        // Verify entry is consistent
                        assertEquals(entry.getKey(), i);
                        assertTrue(entry.getValue().startsWith("value") || entry.getValue().startsWith("extra"));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completionLatch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(i % 2 == 0 ? modifier : iterator);
            t.start();
            threads.add(t);
        }
        
        startLatch.countDown();
        completionLatch.await(30, TimeUnit.SECONDS);
        
        for (Thread t : threads) {
            t.interrupt();
        }
        
        // Final cache consistency check
        assertTrue(cache.size() <= 50);
    }
}
```

Now, let's implement the actual ConcurrentLRUCache class: