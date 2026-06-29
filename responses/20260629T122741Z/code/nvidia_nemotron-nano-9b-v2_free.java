<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->


**Prompt Textfor Model to Generate Code and Tests:**  

---  
**Task:**  
Develop a self-contained Java program implementing a **highly concurrent token-bucket rate limiter** with the following requirements:  
1. **Variable refill rates per client**: Each client (identified by a unique ID) must have a configurable refill rate (tokens per second).  
2. **Burst capacity**: Define a maximum token limit per client (e.g., 100 tokens).  
3. **Thread-safe `acquire(int permits)` method**: This method must block until sufficient permits are available, ensuring no race conditions.  
4. **Lock-free implementation**: Use `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) to manage state without `synchronized` blocks.  
5. **Javadoc**: Provide comprehensive documentation explaining:  
   - The concurrency strategy (e.g., atomic operations, memory visibility).  
   - How memory visibility is maintained (e.g., `volatile` vs. `AtomicLong` semantics).  
   - Overflow prevention (e.g., clamping token counts to burst capacity).  
6. **JUnit 5 Test Suite**:  
   - **Functional correctness**: Verify permits are acquired/released as expected under single-threaded and multi-threaded scenarios.  
   - **Multi-threaded fairness**: Ensure no thread starvation under high contention.  
   - **Stress testing**: Simulate 200 concurrent threads performing random `acquire`/`release` patterns for 30 seconds.  
   - **Refill rate validation**: Confirm the limiter adheres to configured refill rates under load.  

**Constraints:**  
- No `synchronized` blocks or explicit locks.  
- Use `AtomicLong` for atomic operations and `VarHandle` (or `Unsafe`) for low-level memory manipulation if needed.  
- Ensure thread safety and memory consistency guarantees.  

**Deliverables:**  
- A single Java class or set of classes implementing the rate limiter.  
- Javadoc comments for all public methods and critical code sections.  
- A complete JUnit 5 test class with the specified test cases.  

---  
This prompt ensures the generated code and tests meet all functional, concurrency, and performance requirements while emphasizing lock-free design and rigorous validation.
