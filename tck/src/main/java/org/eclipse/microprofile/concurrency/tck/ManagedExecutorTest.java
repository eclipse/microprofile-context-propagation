/*
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.microprofile.concurrency.tck;

import java.io.CharConversionException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrency.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

public class ManagedExecutorTest extends Arquillian {
    /**
     * Maximum tolerated wait for an asynchronous operation to complete.
     * This is important to ensure that tests don't hang waiting for asynchronous operations to complete.
     * Normally these sort of operations will complete in tiny fractions of a second, but we are specifying
     * an extremely generous value here to allow for the widest possible variety of test execution environments.
     */
    private static final long MAX_WAIT_NS = TimeUnit.MINUTES.toNanos(2);

    /**
     * Pool of unmanaged threads (not context-aware) that can be used by tests. 
     */
    private ExecutorService unmanagedThreads;

    @AfterClass
    public void after() {
        unmanagedThreads.shutdownNow();
    }

    @AfterMethod
    public void afterMethod(Method m) {
        System.out.println("<<< END ManagedExecutorTest." + m.getName());
    }

    @BeforeClass
    public void before() {
        unmanagedThreads = Executors.newFixedThreadPool(5);
    }

    @BeforeMethod
    public void beforeMethod(Method m) {
        System.out.println(">>> BEGIN ManagedExecutorTest." + m.getName());
    }

    @Deployment
    public static WebArchive createDeployment() {
        // build a JAR that provides three fake context types: 'Buffer', 'Label', and 'ThreadPriority'
        JavaArchive fakeContextProviders = ShrinkWrap.create(JavaArchive.class, "fakeContextTypes.jar")
                .addPackages(true, "org.eclipse.microprofile.concurrency.tck.contexts.buffer")
                .addPackages(true, "org.eclipse.microprofile.concurrency.tck.contexts.label")
                .addPackage("org.eclipse.microprofile.concurrency.tck.contexts.priority.spi")
                .addAsServiceProvider(ThreadContextProvider.class,
                        BufferContextProvider.class, LabelContextProvider.class, ThreadPriorityContextProvider.class);

        return ShrinkWrap.create(WebArchive.class, ManagedExecutorTest.class.getSimpleName() + ".war")
                .addClass(ManagedExecutorTest.class)
                .addAsLibraries(fakeContextProviders);
    }

    @Test
    public void builderForManagedExecutorIsProvided() {
        Assert.assertNotNull(ManagedExecutor.builder(),
                "MicroProfile Concurrency implementation does not provide a ManagedExecutor builder.");
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages of the completed future that is created
     * by the ManagedExecutor's completedFuture implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void completedFutureDependentStagesRunWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("completedFuture-test-buffer-A"));
            Label.set("completedFuture-test-label");

            CompletableFuture<Long> stage1a = executor.completedFuture(1000L);

            Assert.assertTrue(stage1a.isDone(),
                    "Future created by completedFuture is not complete.");

            Assert.assertFalse(stage1a.isCompletedExceptionally(),
                    "Future created by completedFuture reports exceptional completion.");

            Assert.assertEquals(stage1a.getNow(1234L), Long.valueOf(1000L),
                    "Future created by completedFuture has result that differs from what was specified.");

            // The following incomplete future blocks subsequent stages from running inline on the current thread
            CompletableFuture<Long> stage1b = new CompletableFuture<Long>();

            Buffer.set(new StringBuffer("completedFuture-test-buffer-B"));

            CompletableFuture<Long> stage2 = stage1a.thenCombine(stage1b, (a, b) -> {
                Assert.assertEquals(a, Long.valueOf(1000L),
                        "First value supplied to BiFunction was lost or altered.");

                Assert.assertEquals(b, Long.valueOf(3L),
                        "Second value supplied to BiFunction was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "completedFuture-test-buffer-B",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                return a * b;
            });
            
            Buffer.set(new StringBuffer("completedFuture-test-buffer-C"));

            CompletableFuture<Long> stage3 = stage2.thenApply(i -> {
                Assert.assertEquals(i, Long.valueOf(3000L),
                        "Value supplied to third stage was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "completedFuture-test-buffer-C",
                        "Context type was not propagated to contextual action.");

                // This stage runs inline on the same thread as the test, so alter the
                // context here and later verify that the MicroProfile Concurrency implementation
                // properly restores it to the thread's previous value, which will be
                // completedFuture-test-buffer-E at the point when this runs.
                Buffer.set(new StringBuffer("completedFuture-test-buffer-D"));

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");
                
                return i - 300;
            });

            Buffer.set(new StringBuffer("completedFuture-test-buffer-E"));

            // Complete stage 1b, allowing stage 2 and then 3 to run
            stage1b.complete(3L);

            Assert.assertEquals(stage3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Long.valueOf(2700L),
                    "Unexpected result for stage 3.");

            Assert.assertEquals(stage2.getNow(3333L), Long.valueOf(3000L),
                    "Unexpected or missing result for stage 2.");

            Assert.assertTrue(stage2.isDone(), "Second stage did not transition to done upon completion.");
            Assert.assertTrue(stage3.isDone(), "Third stage did not transition to done upon completion.");

            Assert.assertFalse(stage2.isCompletedExceptionally(), "Second stage should not report exceptional completion.");
            Assert.assertFalse(stage3.isCompletedExceptionally(), "Third stage should not report exceptional completion.");

            // Is context properly restored on current thread?
            Assert.assertEquals(Buffer.get().toString(), "completedFuture-test-buffer-E",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
            Assert.assertEquals(Label.get(), "completedFuture-test-label",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages of the completed future that is created
     * by the ManagedExecutor's completedStage implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void completedStageDependentStagesRunWithContext() throws InterruptedException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("completedStage-test-buffer"));
            Label.set("completedStage-test-label-A");

            CompletionStage<String> stage1 = executor.completedStage("5A");

            // The following incomplete future prevents subsequent stages from completing
            CompletableFuture<Integer> stage2 = new CompletableFuture<Integer>();

            Label.set("completedStage-test-label-B");

            CompletionStage<Integer> stage3 = stage1.thenCompose(s -> {
                Assert.assertEquals(s, "5A",
                        "Value supplied to compose function was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "completedStage-test-label-B",
                        "Context type was not propagated to contextual action.");

                return stage2.thenApply(i -> i + Integer.parseInt(s, 16));
            });

            Label.set("completedStage-test-label-C");

            CompletionStage<Integer> stage4 = stage3.applyToEither(new CompletableFuture<Integer>(), i -> {
                Assert.assertEquals(i, Integer.valueOf(99),
                        "Value supplied to function was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "completedStage-test-label-C",
                        "Context type was not propagated to contextual action.");

                return i + 1;
            });

            Label.set("completedStage-test-label-D");

            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger resultRef = new AtomicInteger();
            stage4.whenComplete((result, failure) -> {
                resultRef.set(result);
                completed.countDown();
            });

            // allow stages 3 and 4 to complete
            stage2.complete(9);

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completion stage did not finish in a reasonable amount of time.");

            Assert.assertEquals(resultRef.get(), 100,
                    "Unexpected result for stage 4.");

            // Is context properly restored on current thread?
            Assert.assertEquals(Buffer.get().toString(), "completedStage-test-buffer",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
            Assert.assertEquals(Label.get(), "completedStage-test-label-D",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages of the completed future that is created
     * by the ManagedExecutor's failedFuture implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void failedFutureDependentStagesRunWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("failedFuture-test-buffer-1"));
            Label.set("failedFuture-test-label");

            CompletableFuture<Character> stage1 = executor.failedFuture(new CharConversionException("A fake exception created by the test"));

            Assert.assertTrue(stage1.isDone(),
                    "Future created by failedFuture is not complete.");

            Assert.assertTrue(stage1.isCompletedExceptionally(),
                    "Future created by failedFuture does not report exceptional completion.");

            try {
                Character result = stage1.getNow('1');
                Assert.fail("Failed future must raise exception. Instead, getNow returned: " + result);
            }
            catch (CompletionException x) {
                if (x.getCause() == null || !(x.getCause() instanceof CharConversionException)
                        || !"A fake exception created by the test".equals(x.getCause().getMessage())) {
                    throw x;
                }
            }

            Buffer.set(new StringBuffer("failedFuture-test-buffer-B"));

            CompletableFuture<Character> stage2a = stage1.exceptionally(x -> {
                Assert.assertEquals(x.getClass(), CharConversionException.class,
                        "Wrong exception class supplied to 'exceptionally' method.");

                Assert.assertEquals(x.getMessage(), "A fake exception created by the test",
                        "Exception message was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "failedFuture-test-buffer-B",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                return 'A';
            });

            // The following incomplete future blocks subsequent stages from running inline on the current thread
            CompletableFuture<Character> stage2b = new CompletableFuture<Character>();

            Buffer.set(new StringBuffer("failedFuture-test-buffer-C"));

            AtomicBoolean stage3Runs = new AtomicBoolean();

            CompletableFuture<Void> stage3 = stage2a.runAfterBoth(stage2b, () -> {
                stage3Runs.set(true);

                Assert.assertEquals(Buffer.get().toString(), "failedFuture-test-buffer-C",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");
            });
            
            Buffer.set(new StringBuffer("failedFuture-test-buffer-D"));

            Assert.assertFalse(stage3.isDone(),
                    "Third stage should not report done until both of the stages upon which it depends complete.");

            // Complete stage 2b, allowing stage 3 to run
            stage2b.complete('B');

            Assert.assertNull(stage3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Unexpected result for stage 3.");

            Assert.assertTrue(stage3Runs.get(),
                    "The Runnable for stage 3 did not run.");

            Assert.assertEquals(stage2a.getNow('F'), Character.valueOf('A'),
                    "Unexpected or missing result for stage 2.");

            Assert.assertTrue(stage2a.isDone(), "Second stage did not transition to done upon completion.");
            Assert.assertTrue(stage3.isDone(), "Third stage did not transition to done upon completion.");

            Assert.assertFalse(stage2a.isCompletedExceptionally(), "Second stage should not report exceptional completion.");
            Assert.assertFalse(stage3.isCompletedExceptionally(), "Third stage should not report exceptional completion.");

            // Is context properly restored on current thread?
            Assert.assertEquals(Buffer.get().toString(), "failedFuture-test-buffer-D",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
            Assert.assertEquals(Label.get(), "failedFuture-test-label",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages of the completed future that is created
     * by the ManagedExecutor's failedStage implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void failedStageDependentStagesRunWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("failedStage-test-buffer"));
            Label.set("failedStage-test-label-A");

            CompletionStage<Integer> stage1 = executor.failedStage(new LinkageError("Error intentionally raised by test case"));

            Label.set("failedStage-test-label-B");

            CompletionStage<Integer> stage2 = stage1.whenComplete((result, failure) -> {
                Assert.assertEquals(failure.getClass(), LinkageError.class,
                        "Wrong exception class supplied to 'whenComplete' method.");

                Assert.assertEquals(failure.getMessage(), "Error intentionally raised by test case",
                        "Error message was lost or altered.");

                Assert.assertNull(result,
                        "Non-null result supplied to whenComplete for failed stage.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "failedStage-test-label-B",
                        "Context type was not propagated to contextual action.");

            });

            Label.set("failedStage-test-label-C");

            CompletableFuture<Integer> future1 = stage1.toCompletableFuture();
            try {
                Integer result = future1.join();
                Assert.fail("The join operation did not raise the error from the failed stage. Instead: " + result);
            }
            catch (CompletionException x) {
                if (x.getCause() == null || !(x.getCause() instanceof LinkageError)
                        || !"Error intentionally raised by test case".equals(x.getCause().getMessage())) {
                    throw x;
                }
            }

            CompletableFuture<Integer> future2 = stage2.toCompletableFuture();
            try {
                Integer result = future2.get();
                Assert.fail("The get operation did not raise the error from the failed stage. Instead: " + result);
            }
            catch (ExecutionException x) {
                if (x.getCause() == null || !(x.getCause() instanceof LinkageError)
                        || !"Error intentionally raised by test case".equals(x.getCause().getMessage())) {
                    throw x;
                }
            }

            Assert.assertEquals(Buffer.get().toString(), "failedStage-test-buffer",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
            Assert.assertEquals(Label.get(), "failedStage-test-label-C",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the ManagedExecutor implementation starts 2 async tasks/actions, and no more,
     * when maxAsync is configured to 2.
     */
    @Test
    public void maxAsync2() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(2)
                .build();

        Phaser barrier = new Phaser(2);
        System.out.println("Unarrived: " + barrier.getUnarrivedParties());
        try {
            // Use up both maxAsync slots on blocking operations and wait for them to start
            Future<Integer> future1 = executor.submit(() -> barrier.awaitAdvance(barrier.arriveAndAwaitAdvance()));
            CompletableFuture<Integer> future2 = executor.supplyAsync(() -> barrier.awaitAdvance(barrier.arriveAndAwaitAdvance()));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // This data structure holds the results of tasks which shouldn't be able to run yet
            LinkedBlockingQueue<String> results = new LinkedBlockingQueue<String>();

            // Submit additional tasks/actions for async execution.
            // These should queue, but otherwise be unable to start yet due to maxAsync=2.
            CompletableFuture<Void> future3 = executor.runAsync(() -> results.offer("Result3"));
            CompletableFuture<Boolean> future4 = executor.supplyAsync(() -> results.offer("Result4"));
            Future<Boolean> future5 = executor.submit(() -> results.offer("Result5"));
            CompletableFuture<Boolean> future6 = executor.completedFuture("6")
                    .thenApplyAsync(s -> results.offer("Result" + s));

            // Detect whether any of the above tasks/actions run within the next 5 seconds
            Assert.assertNull(results.poll(5, TimeUnit.SECONDS),
                    "Should not be able start more than 2 async tasks when maxAsync is 2.");

            // unblock and allow tasks to finish
            barrier.arrive();
            barrier.arrive(); // there are 2 parties in each phase

            Assert.assertNotNull(results.poll(MAX_WAIT_NS, TimeUnit.SECONDS), "None of the queued tasks ran.");
            Assert.assertNotNull(results.poll(MAX_WAIT_NS, TimeUnit.SECONDS), "Only 1 of the queued tasks ran.");
            Assert.assertNotNull(results.poll(MAX_WAIT_NS, TimeUnit.SECONDS), "Only 2 of the queued tasks ran.");
            Assert.assertNotNull(results.poll(MAX_WAIT_NS, TimeUnit.SECONDS), "Only 3 of the queued tasks ran.");

            Assert.assertEquals(future1.get(), Integer.valueOf(2), "Unexpected result of first task.");
            Assert.assertEquals(future2.get(), Integer.valueOf(2), "Unexpected result of second task.");
            Assert.assertNull(future3.join(), "Unexpected result of third task.");
            Assert.assertEquals(future4.join(), Boolean.TRUE, "Unexpected result of fourth task.");
            Assert.assertEquals(future5.get(), Boolean.TRUE, "Unexpected result of fifth task.");
            Assert.assertEquals(future6.get(), Boolean.TRUE, "Unexpected result of sixth task.");
        }
        finally {
            barrier.forceTermination();
            executor.shutdownNow();
        }
    }

    /**
     * Attempt to specify invalid values (less than -1 and 0) for maxAsync.
     * Require this to be rejected upon the maxQueued operation per JavaDoc
     * rather than from the build method.
     */
    @Test
    public void maxAsyncInvalidValues() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor.Builder builder = ManagedExecutor.builder();

        try {
            builder.maxAsync(-10);
            Assert.fail("ManagedExecutor builder permitted value of -10 for maxAsync.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        try {
            builder.maxAsync(-2);
            Assert.fail("ManagedExecutor builder permitted value of -2 for maxAsync.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        try {
            builder.maxQueued(0);
            Assert.fail("ManagedExecutor builder permitted value of 0 for maxAsync.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        // builder remains usable
        ManagedExecutor executor = builder.build();
        try {
            // neither of the invalid values apply - can run a task
            Future<String> future = executor.submit(() -> "it worked!");
            Assert.assertEquals(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "it worked!",
                    "Task had missing or unexpected result.");
        }
        finally {
            executor.shutdownNow();
        }
    }

    /**
     * Verify that 3 tasks/actions, and no more, can be queued when maxQueued is configured to 3.
     */
    @Test
    public void maxQueued3() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(1)
                .maxQueued(3)
                .build();

        Phaser barrier = new Phaser(1);
        try {
            // First, use up the single maxAsync slot with a blocking task and wait for it to start
            executor.submit(() -> barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Use up first queue position
            Future<Integer> future1 = executor.submit(() -> 101);

            // Use up second queue position
            CompletableFuture<Void> future2 = executor.runAsync(() -> System.out.println("second task running"));

            // Use up third queue position
            Future<Integer> future3 = executor.submit(() -> 103);

            // Fourth attempt to queue a task must be rejected
            try {
                Future<Integer> future4 = executor.submit(() -> 104);
                Assert.fail("Exceeded maxQueued of 3. Future for 4th queued task/action is " + future4);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            // Fifth attempt to queue a task must also be rejected
            try {
                CompletableFuture<Integer> future5 = executor.supplyAsync(() -> 105);
                Assert.fail("Exceeded maxQueued of 3. Future for 5th queued task/action is " + future5);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            // unblock and allow tasks to finish
            barrier.arrive();

            Assert.assertEquals(future1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(101),
                    "Unexpected result of first task.");

            Assert.assertNull(future2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Unexpected result of second task.");

            // At least 2 queue positions must be available at this point
            Future<Integer> future6 = executor.submit(() -> 106);
            CompletableFuture<Integer> future7 = executor.supplyAsync(() -> 107);

            Assert.assertEquals(future3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(103),
                    "Unexpected result of third task.");

            Assert.assertEquals(future6.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(106),
                    "Unexpected result of sixth task.");

            Assert.assertEquals(future7.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(107),
                    "Unexpected result of seventh task.");
        }
        finally {
            barrier.forceTermination();
            executor.shutdownNow();
        }
    }

    /**
     * Attempt to specify invalid values (less than -1 and 0) for maxQueued.
     * Require this to be rejected upon the maxQueued operation per JavaDoc
     * rather than from the build method.
     */
    @Test
    public void maxQueuedInvalidValues() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor.Builder builder = ManagedExecutor.builder();

        try {
            builder.maxQueued(-2);
            Assert.fail("ManagedExecutor builder permitted value of -2 for maxQueued.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        try {
            builder.maxQueued(0);
            Assert.fail("ManagedExecutor builder permitted value of 0 for maxQueued.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        // builder remains usable
        ManagedExecutor executor = builder.build();
        try {
            // neither of the invalid values apply - can queue a task and run it
            Future<String> future = executor.submit(() -> "successful!");
            Assert.assertEquals(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "successful!",
                    "Task had missing or unexpected result.");
        }
        finally {
            executor.shutdownNow();
        }
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages of the incomplete future that is created
     * by the ManagedExecutor's newIncompleteFuture implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void newIncompleteFutureDependentStagesRunWithContext() throws ExecutionException, InterruptedException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            CompletableFuture<Integer> stage1 = executor.newIncompleteFuture();

            Assert.assertFalse(stage1.isDone(),
                    "Completable future created by newIncompleteFuture did not start out as incomplete.");

            // Set non-default values
            Buffer.get().append("newIncompleteFuture-test-buffer");
            Label.set("newIncompleteFuture-test-label-A");

            CompletableFuture<Integer> stage2 = stage1.thenApply(i -> {
                Assert.assertEquals(i, Integer.valueOf(10),
                        "Value supplied to second stage was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "newIncompleteFuture-test-label-A",
                        "Context type was not correctly propagated to contextual action.");
                
                return i * 2;
            });

            Label.set("newIncompleteFuture-test-label-B");

            CompletableFuture<Integer> stage3 = stage2.thenApply(i -> {
                Assert.assertEquals(i, Integer.valueOf(20),
                        "Value supplied to third stage was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "newIncompleteFuture-test-label-B",
                        "Context type was not correctly propagated to contextual action.");
                
                return i + 10;
            });

            Label.set("newIncompleteFuture-test-label-C");

            // To avoid the possibility that CompletableFuture.get might cause the action to run
            // on the current thread, which would bypass the intent of testing context propagation,
            // use a countdown latch to independently wait for completion.
            CountDownLatch completed = new CountDownLatch(1);
            stage3.whenComplete((result, failure) -> completed.countDown());

            Assert.assertTrue(stage1.complete(10),
                    "Unable to complete the future that was created by newIncompleteFuture.");

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completable future did not finish in a reasonable amount of time.");

            Assert.assertTrue(stage1.isDone(), "First stage did not transition to done upon completion.");
            Assert.assertTrue(stage2.isDone(), "Second stage did not transition to done upon completion.");
            Assert.assertTrue(stage3.isDone(), "Third stage did not transition to done upon completion.");

            Assert.assertEquals(stage1.get(), Integer.valueOf(10),
                    "Result of first stage does not match the value with which it was completed.");

            Assert.assertEquals(stage2.getNow(22), Integer.valueOf(20),
                    "Result of second stage was lost or altered.");

            Assert.assertEquals(stage3.join(), Integer.valueOf(30),
                    "Result of third stage was lost or altered.");

            Assert.assertFalse(stage1.isCompletedExceptionally(), "First stage should not report exceptional completion.");
            Assert.assertFalse(stage2.isCompletedExceptionally(), "Second stage should not report exceptional completion.");
            Assert.assertFalse(stage3.isCompletedExceptionally(), "Third stage should not report exceptional completion.");

            // Is context properly restored on current thread?
            Assert.assertEquals(Buffer.get().toString(), "newIncompleteFuture-test-buffer",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
            Assert.assertEquals(Label.get(), "newIncompleteFuture-test-label-C",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages as well as the initial stage created
     * by the ManagedExecutor's runAsync implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void runAsyncStageAndDependentStagesRunWithContext() throws ExecutionException, InterruptedException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("runAsync-test-buffer");
            Label.set("runAsync-test-label-A");

            CompletableFuture<Void> stage1 = executor.runAsync(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "runAsync-test-label-A",
                        "Context type was not correctly propagated to contextual action.");                
            });

            Label.set("runAsync-test-label-B");

            CompletableFuture<Void> stage2 = stage1.thenRunAsync(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "runAsync-test-label-B",
                        "Context type was not correctly propagated to contextual action.");                
            });

            Label.set("runAsync-test-label-C");

            CompletableFuture<Void> stage3 = stage2.thenRunAsync(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) should not be propagated when specifying a non-managed executor.");

                Assert.assertEquals(Label.get(), "",
                        "Context type (Label) should not be propagated when specifying a non-managed executor.");                
            }, unmanagedThreads);

            Label.set("runAsync-test-label-D");

            CompletableFuture<Void> stage4 = stage3.thenRun(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "runAsync-test-label-D",
                        "Context type was not correctly propagated to contextual action.");

                throw new NegativeArraySizeException("Fake exception raised by test");
            });

            Label.set("runAsync-test-label-E");

            CompletableFuture<Character> stage5 = stage4.handle((v, x) -> {
                Assert.assertNull(v,
                        "Non-null value supplied to 'handle' method.");

                Assert.assertEquals(x.getClass(), CompletionException.class,
                        "Exception parameter to 'handle' method is inconsistent with java.util.concurrent.CompletableFuture.");

                Throwable cause = x.getCause();
                Assert.assertNotNull(cause,
                        "CompletionException supplied to 'handle' method lacks cause.");

                Assert.assertEquals(cause.getClass(), NegativeArraySizeException.class,
                        "Wrong exception class supplied to 'handle' method.");

                Assert.assertEquals(cause.getMessage(), "Fake exception raised by test",
                        "Exception message was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "runAsync-test-label-E",
                        "Context type was not correctly propagated to contextual action.");

                return 'E';
            });

            Label.set("runAsync-test-label-F");

            CompletableFuture<Void> stage6 = stage5.thenAccept(c -> {
                Assert.assertEquals(c, Character.valueOf('E'),
                        "Value supplied to Consumer was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "runAsync-test-label-F",
                        "Context type was not correctly propagated to contextual action.");
            });

            Label.set("runAsync-test-label-G");

            // use a countdown latch to independently wait for completion.
            CountDownLatch completed = new CountDownLatch(1);
            stage6.whenComplete((result, failure) -> completed.countDown());

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completable future did not finish in a reasonable amount of time.");

            Assert.assertTrue(stage1.isDone(), "First stage did not transition to done upon completion.");
            Assert.assertTrue(stage2.isDone(), "Second stage did not transition to done upon completion.");
            Assert.assertTrue(stage3.isDone(), "Third stage did not transition to done upon completion.");
            Assert.assertTrue(stage4.isDone(), "Fourth stage did not transition to done upon completion.");
            Assert.assertTrue(stage5.isDone(), "Fifth stage did not transition to done upon completion.");
            Assert.assertTrue(stage6.isDone(), "Sixth stage did not transition to done upon completion.");

            try {
                Object result = stage4.join();
                Assert.fail("The join method must not return value " + result + " for stage with exceptional completion.");
            }
            catch (CompletionException x) {
                if (x.getCause() == null || !(x.getCause() instanceof NegativeArraySizeException)
                        || !"Fake exception raised by test".equals(x.getCause().getMessage())) {
                    throw x;
                }
            }

            Assert.assertEquals(stage5.join(), Character.valueOf('E'),
                    "Return value of 'handle' method was lost or altered.");

            Assert.assertFalse(stage1.isCompletedExceptionally(), "First stage should not report exceptional completion.");
            Assert.assertFalse(stage2.isCompletedExceptionally(), "Second stage should not report exceptional completion.");
            Assert.assertFalse(stage3.isCompletedExceptionally(), "Third stage should not report exceptional completion.");
            Assert.assertTrue(stage4.isCompletedExceptionally(), "Fourth stage did not report exceptional completion.");
            Assert.assertFalse(stage5.isCompletedExceptionally(), "Fifth stage should not report exceptional completion.");
            Assert.assertFalse(stage6.isCompletedExceptionally(), "Sixth stage should not report exceptional completion.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all dependent stages as well as the initial stage created
     * by the ManagedExecutor's supplyAsync implementation. Thread context is captured
     * at each point where a dependent stage is added, rather than solely upon creation of the
     * initial stage or construction of the builder.
     */
    @Test
    public void supplyAsyncStageAndDependentStagesRunWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("supplyAsync-test-buffer-A"));
            Label.set("supplyAsync-test-label");

            CompletableFuture<Long> stage1 = executor.supplyAsync(() -> {
                Assert.assertEquals(Buffer.get().toString(), "supplyAsync-test-buffer-A",
                        "Context type was not correctly propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                return 100L;
            });

            Buffer.set(new StringBuffer("supplyAsync-test-buffer-B"));

            CompletableFuture<Long> stage2 = stage1.thenApply(i -> {
                Assert.assertEquals(i, Long.valueOf(100),
                        "Value supplied to Function was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "supplyAsync-test-buffer-B",
                        "Context type was not correctly propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                return 200L;
            });

            Buffer.set(new StringBuffer("supplyAsync-test-buffer-C"));

            CompletableFuture<Long> stage3 = stage1.thenApply(i -> {
                Assert.assertEquals(i, Long.valueOf(100),
                        "Value supplied to Function was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "supplyAsync-test-buffer-C",
                        "Context type was not correctly propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                return 300L;
            });

            Buffer.set(new StringBuffer("supplyAsync-test-buffer-D"));

            CompletableFuture<Void> stage4 = stage2.thenAcceptBoth(stage3, (i, j) -> {
                Assert.assertEquals(i, Long.valueOf(200),
                        "First value supplied to BiConsumer was lost or altered.");

                Assert.assertEquals(j, Long.valueOf(300),
                        "Second value supplied to BiConsumer was lost or altered.");

                Assert.assertEquals(Buffer.get().toString(), "supplyAsync-test-buffer-D",
                        "Context type was not correctly propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");
            });

            Buffer.set(new StringBuffer("supplyAsync-test-buffer-D"));

            // use a countdown latch to independently wait for completion.
            CountDownLatch completed = new CountDownLatch(1);
            stage4.handleAsync((result, x) -> {
                completed.countDown();
                return result;
            }, unmanagedThreads);

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completable future did not finish in a reasonable amount of time.");

            Assert.assertEquals(stage1.get(10, TimeUnit.SECONDS), Long.valueOf(100),
                    "Unexpected result for first stage.");

            Assert.assertEquals(stage2.join(), Long.valueOf(200),
                    "Unexpected result for second stage.");

            Assert.assertEquals(stage3.getNow(33L), Long.valueOf(300),
                    "Unexpected result for third stage.");

            Assert.assertNull(stage4.join(),
                    "Unexpected result for fourth stage.");

            // Is context properly restored on current thread?
            Assert.assertEquals(Buffer.get().toString(), "supplyAsync-test-buffer-D",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
            Assert.assertEquals(Label.get(), "supplyAsync-test-label",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }
}
