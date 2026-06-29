<!-- model: poolside/laguna-m.1:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->

Create a self-contained Java program implementing a highly concurrent token-bucket rate limiter with the following specifications:  

**Core Implementation Requirements**  
1. **Class Structure**: Define a class `ConcurrentTokenBucketRateLimiter` in the default package.  
2. **Constructor**: Accept `double refillRate` (tokens per second) and `int burstCapacity` (maximum tokens).  
3. **Thread-Safe `acquire(int permits)` Method**:  
   - Blocks until the requested number of permits is available.  
   - Uses lock-free algorithms with `AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`).  
   - No explicit `synchronized` blocks or locks.  
4. **Concurrency Strategy**:  
   - Maintain token count and last refill timestamp using atomic variables.  
   - Use compare-and-set (CAS) loops for atomic updates.  
   - Calculate token refills based on elapsed time since last refill.  
5. **Memory Visibility**: Ensure visibility of token state across threads via `AtomicLong` and `VarHandle` on the timestamp field.  
6. **Overflow Prevention**: Cap token count at `burstCapacity` during refills to prevent overflow.  

**Javadoc Requirements**  
Include comprehensive Javadoc explaining:  
- How the lock-free algorithm works (e.g., CAS loops for token updates).  
- Memory-visibility guarantees provided by `AtomicLong` and `VarHandle`.  
- Overflow prevention logic (e.g., capping tokens at burst capacity).  

**JUnit 5 Test Suite Requirements**  
1. **Functional Correctness Tests**:  
   - Verify that acquiring permits succeeds when tokens are available.  
   - Test blocking behavior when insufficient tokens exist.  
   - Validate token refill rate and burst capacity limits.  
2. **Multi-Threaded Fairness Under Contention**:  
   - Launch multiple threads (e.g., 10) competing for permits.  
   - Ensure no thread is indefinitely starved.  
3. **Stress Testing**:  
   - Run 200 concurrent threads performing random acquire/release operations for 30+ seconds.  
   - Track total permits acquired to verify no overflows or underflows.  
4. **Refill Rate Verification Under Load**:  
   - Configure a limiter with a known rate (e.g., 1000 permits/sec).  
   - Measure actual permits acquired over time to ensure compliance with rate limits.  

**Additional Constraints**  
- Use `VarHandle` (preferred) or `sun.misc.Unsafe` for atomic timestamp access.  
- Do not use external libraries beyond standard Java and JUnit 5.  
- Ensure the program compiles and runs with Java 17+.  

Provide the full implementation and test suite in a single Java file or package, including all necessary imports and annotations.
