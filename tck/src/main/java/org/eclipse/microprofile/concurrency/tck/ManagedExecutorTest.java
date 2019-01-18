/*
 * Copyright (c) 2018,2019 Contributors to the Eclipse Foundation
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrency.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
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
    public void afterMethod(Method m, ITestResult result) {
        System.out.println("<<< END " + m.getClass().getSimpleName() + '.' + m.getName() + (result.isSuccess() ? " SUCCESS" : " FAILED"));
        Throwable failure = result.getThrowable();
        if (failure != null) {
            failure.printStackTrace(System.out);
        }
    }

    @BeforeClass
    public void before() {
        unmanagedThreads = Executors.newFixedThreadPool(5);
    }

    @BeforeMethod
    public void beforeMethod(Method m) {
        System.out.println(">>> BEGIN " + m.getClass().getSimpleName() + '.' + m.getName());
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
     * Verify the MicroProfile Concurrency implementation of propagate(), and cleared()
     * for MangedExecutor.Builder.
     */
    @Test
    public void contextControlsForManagedExecutorBuilder() throws InterruptedException, ExecutionException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .cleared(Label.CONTEXT_NAME)
                .maxAsync(-1)
                .maxQueued(-1)
                .build();

        try {
            ManagedExecutor.builder()
            .propagated(Buffer.CONTEXT_NAME)
            .cleared(Label.CONTEXT_NAME, Buffer.CONTEXT_NAME)
            .maxAsync(-1)
            .maxQueued(-1)
            .build();
            Assert.fail("ManagedExecutor.Builder.build() should throw an IllegalStateException for set overlap between propagated and cleared");
        }
        catch (IllegalStateException ISE) {
            // test passes
        }

        try {
            ManagedExecutor.builder()
            .propagated(Buffer.CONTEXT_NAME, "BOGUS_CONTEXT")
            .cleared(Label.CONTEXT_NAME)
            .maxAsync(-1)
            .maxQueued(-1)
            .build();
            Assert.fail("ManagedExecutor.Builder.build() should throw an IllegalStateException for a nonexistent thread context type");
        }
        catch (IllegalStateException ISE) {
            // test passes
        }

        try {
            // Set non-default values
            Buffer.get().append("contextControls-test-buffer-A");
            Label.set("contextControls-test-label-A");

            Future<Void> future = executor.submit(() -> {
                Assert.assertEquals(Buffer.get().toString(), "contextControls-test-buffer-A",
                        "Context type was not propagated to contextual action.");

                Buffer.get().append("-B");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Label.set("contextControls-test-label-B");

                return null;
            });

            Assert.assertNull(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Unexpected result of task.");

            Assert.assertEquals(Buffer.get().toString(), "contextControls-test-buffer-A-B",
                    "Context type was not propagated to contextual action.");

            Assert.assertEquals(Label.get(), "contextControls-test-label-A",
                    "Context type was not left unchanged by contextual action.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is cleared per the configuration of the ManagedExecutor builder
     * for all tasks that are executed via the execute method. This test supplies the ManagedExecutor
     * to a Java SE CompletableFuture, which invokes the execute method to run tasks asynchronously.
     */
    @Test
    public void executedTaskRunsWithClearedContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            Buffer.set(new StringBuffer("executed-task-test-buffer-A"));
            Label.set("executed-task-test-label-A");

            CompletableFuture<Integer> cf1 = new CompletableFuture<Integer>();

            CompletableFuture<Void> cf2 = cf1.thenAcceptAsync(i -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Label.set("executed-task-test-label-B");
            }, executor);

            cf1.complete(1000);

            cf2.join();

            Assert.assertEquals(Buffer.get().toString(), "executed-task-test-buffer-A",
                    "Context unexpectedly changed on thread.");
            Assert.assertEquals(Label.get(), "executed-task-test-label-A",
                    "Context unexpectedly changed on thread.");
        }
        finally {
            executor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that thread context is propagated per the configuration of the ManagedExecutor builder
     * for all tasks that are executed via the execute method.
     */
    @Test
    public void executedTaskRunsWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME, Label.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            Buffer.set(new StringBuffer("executed-task-test-buffer-C"));
            Label.set("executed-task-test-label-C");

            CompletableFuture<String> result = new CompletableFuture<String>();
            executor.execute(() -> {
                try {
                    Assert.assertEquals(Buffer.get().toString(), "executed-task-test-buffer-C",
                            "Context type that is configured to be propagated was not propagated.");

                    Assert.assertEquals(Label.get(), "executed-task-test-label-C",
                            "Context type that is configured to be propagated was not propagated.");

                    Label.set("executed-task-test-label-D");

                    result.complete("successful");
                }
                catch (Throwable x) {
                    result.completeExceptionally(x);
                }
            });

            // Force exception to be raised, if any
            result.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            Assert.assertEquals(Buffer.get().toString(), "executed-task-test-buffer-C",
                    "Context unexpectedly changed on thread.");
            Assert.assertEquals(Label.get(), "executed-task-test-label-C",
                    "Context unexpectedly changed on thread.");
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
     * Verify that the ManagedExecutor shutdownNow method prevents additional tasks from being submitted
     * and cancels tasks that are currently in progress or queued.
     * Also verify that once the tasks and actions terminate, the ManagedExecutor transitions to terminated state.
     */
    @Test
    public void shutdownNowPreventsAdditionalSubmitsAndCancelsTasks() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(1)
                .maxQueued(4)
                .build();

        Phaser barrier = new Phaser(1);
        Future<Integer> future1;
        CompletableFuture<Void> future2;
        CompletableFuture<String> future3;
        Future<String> future4;
        Future<Boolean> future5 = null;
        AtomicInteger task2ResultRef = new AtomicInteger(-1);
        List<Runnable> tasksThatDidNotStart;
        try {
            try {
                // Block the single maxAsync slot
                future1 = executor.submit(() -> barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1));
                barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

                // Queue up some tasks
                future2 = executor.runAsync(() -> task2ResultRef.set(20));
                future3 = executor.supplyAsync(() -> "Q30");
                future4 = executor.submit(() -> "Q40");

                Assert.assertFalse(executor.isTerminated(),
                        "ManagedExecutor should not report being terminated when tasks are still running/queued.");

                // Await termination from a different executor,
                future5 = unmanagedThreads.submit(() -> executor.awaitTermination(MAX_WAIT_NS, TimeUnit.NANOSECONDS));
            }
            finally {
                tasksThatDidNotStart = executor.shutdownNow();
            }

            Assert.assertNotNull(tasksThatDidNotStart,
                    "Null list returned by ManagedExecutor.shutdownNow.");

            Assert.assertEquals(tasksThatDidNotStart.size(), 3,
                    "List of tasks that did not start should correspond to the tasks/actions that are queued. Observed: " +
                    tasksThatDidNotStart);

            Assert.assertTrue(executor.isShutdown(),
                    "ManagedExecutor reported that it has not been shut down after we shut it down.");

            // additional submits of async tasks/actions must be rejected
            try {
                Future<Integer> future6 = executor.submit(() -> 60);
                Assert.fail("Should not be possible to submit new task after shutdownNow. Future: " + future6);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            try {
                Future<Integer> future7 = executor.supplyAsync(() -> 70);
                Assert.fail("Should not be possible to create new async action after shutdownNow. Future: " + future7);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            Assert.assertTrue(executor.awaitTermination(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "ManagedExecutor did not reach terminated state within a reasonable amount of time.");

            Assert.assertTrue(executor.isTerminated(),
                    "ManagedExecutor did not report being terminated after running/queued tasks were canceled and ended.");

            // assert that future 1 was completed but ended with ExecutionException because it was running on executor
            // when shutdownNow() was invoked
            try {
                Assert.assertTrue(future1.isDone());
                Integer result1 = future1.get(1, TimeUnit.SECONDS);
                Assert.fail("Running task should not complete successfully after shutdownNow. Result: " + result1);
            }
            catch (ExecutionException x) {
                if (!(x.getCause() instanceof InterruptedException)) {
                    throw x;
                }
                // test passes
            }
            catch (CancellationException x) {
                // test passes, impl may chose to mark such task as cancelled
            }

            // assert that future 2,3,4 weren't executed (based on impl they are either neither done nor cancelled
            // or they are done and cancelled)
            if (future2.isDone()) {
                try {
                    Object result2 = future2.join();
                    Assert.fail("Queued action should not run after shutdownNow. Result: " + result2);
                }
                catch (CancellationException x) {
                    // test passes
                }
            }
            else {
                Assert.assertTrue(!future2.isCancelled(), "Running task should not complete after shutdownNow() invocation.");
            }

            if (future3.isDone()) {
                try {
                    String result3 = future3.getNow("333");
                    Assert.fail("Queued action should not run after shutdownNow. Result: " + result3);
                }
                catch (CancellationException x) {
                    // test passes
                }
            }
            else {
                Assert.assertTrue(!future3.isCancelled(), "Running task should not complete after shutdownNow() invocation.");
            }

            if (future4.isDone()) {
                try {
                    String result4 = future4.get(1, TimeUnit.SECONDS);
                    Assert.fail("Queued task should not run after shutdownNow. Result: " + result4);
                }
                catch (CancellationException x) {
                    // test passes
                }
            }
            else {
                Assert.assertTrue(!future4.isCancelled(), "Running task should not complete after shutdownNow() invocation.");
            }

            Assert.assertEquals(task2ResultRef.get(), -1,
                    "Queued action should not start running after shutdownNow.");

            Assert.assertEquals(future5.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Boolean.TRUE,
                    "Notification of termination was not received in a reasonable amount of time by the " +
                    "awaitTermination request that was issued before shutdownNow");
        }
        finally {
            barrier.forceTermination();

            if (future5 != null) {
                future5.cancel(true);
            }
        }
    }

    /**
     * Verify that the ManagedExecutor shutdown method prevents additional tasks from being submitted
     * but does not interfere with tasks and actions that are running or queued.
     * Also verify that once the tasks and actions finish, the ManagedExecutor transitions to terminated state.
     */
    @Test
    public void shutdownPreventsAdditionalSubmits() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(1)
                .maxQueued(10)
                .build();

        Phaser barrier = new Phaser(1);
        CompletableFuture<Integer> future1;
        CompletableFuture<String> future2;
        Future<String> future3;
        Future<Boolean> future4 = null;
        Future<Boolean> future5 = null;
        try {
            try {
                // Block the single maxAsync slot
                future1 = executor.supplyAsync(() -> barrier.awaitAdvance(barrier.arrive() + 1));
                barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

                // Queue up some tasks
                future2 = executor.supplyAsync(() -> "Q2");
                future3 = executor.submit(() -> "Q3");

                Assert.assertFalse(executor.isShutdown(),
                        "ManagedExecutor reportd that it has been shut down even though we did not shut it down yet.");

                // Await termination from a different executor,
                future4 = unmanagedThreads.submit(() -> executor.awaitTermination(MAX_WAIT_NS, TimeUnit.NANOSECONDS));
            }
            finally {
                executor.shutdown();
            }

            Assert.assertTrue(executor.isShutdown(),
                    "ManagedExecutor reported that it has not been shut down after we shut it down.");

            // additional submits of async tasks/actions must be rejected
            try {
                Future<Integer> future6 = executor.submit(() -> 60);
                Assert.fail("Should not be possible to submit new task after shutdown. Future: " + future6);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            try {
                Future<Integer> future7 = executor.supplyAsync(() -> 70);
                Assert.fail("Should not be possible to create new async action after shutdown. Future: " + future7);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            Assert.assertFalse(future1.isDone(),
                    "Task should remain running after shutdown is invoked.");

            Assert.assertFalse(future2.isDone(),
                    "Action should remain queued after shutdown is invoked.");

            Assert.assertFalse(future3.isDone(),
                    "Task should remain queued after shutdown is invoked.");

            Assert.assertFalse(executor.isTerminated(),
                    "ManagedExecutor should not report being terminated when tasks are still running/queued.");

            // Extra invocation of shutdown has no effect per ExecutorService JavaDoc,
            executor.shutdown();

            // Await termination from a different executor,
            future5 = unmanagedThreads.submit(() -> executor.awaitTermination(MAX_WAIT_NS, TimeUnit.NANOSECONDS));

            // Let the running task finish and the queued tasks run
            barrier.arrive();

            Assert.assertEquals(future1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(2),
                    "Unexpected result for action that was running when shutdown was requested.");

            Assert.assertEquals(future2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q2",
                    "Unexpected result for action that was in the queue when shutdown was requested.");

            Assert.assertEquals(future3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q3",
                    "Unexpected result for task that was in the queue when shutdown was requested.");

            Assert.assertEquals(future4.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Boolean.TRUE,
                    "Notification of termination was not received in a reasonable amount of time by the " +
                    "awaitTermination request that was issued prior to shutdown");

            Assert.assertEquals(future5.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Boolean.TRUE,
                    "Notification of termination was not received in a reasonable amount of time by the " +
                    "awaitTermination request that was issued after shutdown");

            Assert.assertTrue(executor.isTerminated(),
                    "ManagedExecutor did not report being terminated after running/queued tasks completed.");
        }
        finally {
            barrier.forceTermination();

            if (future4 != null) {
                future4.cancel(true);
            }

            if (future5 != null) {
                future5.cancel(true);
            }
        }
    }

    /**
     * Verify that the ManagedExecutor.Builder can be used to create multiple ManagedExecutors with 
     * different configured contexts.
     */
    @Test
    public void reuseManagedExecutorBuilder() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor.Builder builder = ManagedExecutor.builder()
                .propagated()
                .cleared(Buffer.CONTEXT_NAME);
        
        ManagedExecutor clearingExecutor = builder.build();

        builder.propagated(Buffer.CONTEXT_NAME);
        try {
            builder.build();
            Assert.fail("ManagedExecutor.Builder.build() should throw an IllegalStateException for set overlap between propagated and cleared");
        }
        catch (IllegalStateException ISE) {
            // test passes
        }

        builder.cleared();
        ManagedExecutor propagatingExecutor = builder.build();

        try {
            // Set non-default value
            Buffer.set(new StringBuffer("reuseBuilder-test-buffer-A"));

            Future<Void> clearedFuture = clearingExecutor.completedFuture(1).thenRun(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Buffer.set(new StringBuffer("reuseBuilder-test-buffer-B"));
            });

            Future<Void> propagatedFuture = propagatingExecutor.completedFuture(1).thenRunAsync(() -> {
                Assert.assertEquals(Buffer.get().toString(), "reuseBuilder-test-buffer-A",
                        "Context type was not propagated to contextual action.");

                Buffer.set(new StringBuffer("reuseBuilder-test-buffer-C"));
            });

            Assert.assertNull(propagatedFuture.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable.");

            Assert.assertNull(clearedFuture.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable.");

            Assert.assertEquals(Buffer.get().toString(), "reuseBuilder-test-buffer-A",
                    "Previous context (Buffer) was not restored after context was propagated for contextual action.");
        }
        finally {
            clearingExecutor.shutdownNow();
            propagatingExecutor.shutdownNow();
            // Restore original value
            Buffer.set(null);
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
     * ManagedExecutor builder for all tasks that are submitted via the submit(Callable),
     * submit(Runnable) and submit(Runnable, result) methods.
     */
    @Test
    public void submittedTasksRunWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Label.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            Buffer.set(new StringBuffer("submitted-tasks-test-buffer-A"));
            Label.set("submitted-tasks-test-label-A");

            Future<?> futureA = executor.submit(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "submitted-tasks-test-label-A",
                        "Context type was not correctly propagated to contextual action.");

                throw new Error("Fake error intentionally raised by test Runnable.");
            });

            Label.set("submitted-tasks-test-label-B");

            Future<String> futureB = executor.submit(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "submitted-tasks-test-label-B",
                        "Context type was not correctly propagated to contextual action.");
            }, "Task-B-Result");

            Label.set("submitted-tasks-test-label-C");

            Future<String> futureC = executor.submit(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "submitted-tasks-test-label-C",
                        "Context type was not correctly propagated to contextual action.");

                return "Task-C-Result";
            });

            try {
                Object result = futureA.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);
                Assert.fail("Result of " + result + " returned for Runnable that throws an Error.");
            }
            catch (ExecutionException x) {
                if (x.getCause() == null
                        || (!(x.getCause() instanceof Error))
                        || (!"Fake error intentionally raised by test Runnable.".equals(x.getCause().getMessage()))) {
                    throw x;
                }
            }

            Assert.assertEquals("Task-B-Result", futureB.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Result does not match the predetermined result that was specified when submitting the Runnable.");

            Assert.assertEquals("Task-C-Result", futureC.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Result does not match the result returned by the Callable.");

            Assert.assertTrue(futureA.isDone(),
                    "Future for first Runnable should report being done after test case awaits its completion.");

            Assert.assertTrue(futureB.isDone(),
                    "Future for second Runnable should report being done after test case awaits its completion.");

            Assert.assertTrue(futureC.isDone(),
                    "Future for Callable should report being done after test case awaits its completion.");

            Assert.assertFalse(futureA.isCancelled(),
                    "Future for first Runnable should not be canceled because the test case did not cancel it.");

            Assert.assertFalse(futureB.isCancelled(),
                    "Future for second Runnable should not be canceled because the test case did not cancel it.");

            Assert.assertFalse(futureC.isCancelled(),
                    "Future for Callable should not be canceled because the test case did not cancel it.");
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

    /**
     * Verify that thread context is captured and propagated per the configuration of the
     * ManagedExecutor builder for all tasks that are submitted via the ManagedExecutor's
     * timed invokeAll operation. Thread context is captured at the point where invokeAll is
     * invoked, rather than upon creation of the executor or construction of the builder.
     */
    @Test
    public void timedInvokeAllRunsTasksWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("timed-invokeAll-test-buffer-A"));
            Label.set("timed-invokeAll-test-label-A");

            List<Future<String>> futures = executor.invokeAll(Arrays.<Callable<String>>asList(
                    () -> {
                        Assert.assertEquals(Buffer.get().toString(), "timed-invokeAll-test-buffer-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Label.get(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("timed-invokeAll-test-label-B");
                        Buffer.set(new StringBuffer("timed-invokeAll-test-buffer-B"));
                        return "B";
                    },
                    () -> {
                        Assert.assertEquals(Buffer.get().toString(), "timed-invokeAll-test-buffer-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Label.get(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("timed-invokeAll-test-label-C");
                        Buffer.set(new StringBuffer("invokeAll-test-buffer-C"));
                        return "C";
                    }),
                    MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            Assert.assertEquals(futures.size(), 2,
                    "Number of futures does not match the number of tasks. " + futures);

            Assert.assertTrue(futures.get(0).isDone(),
                    "Future for first task does not indicate it is done after invokeAll.");
            Assert.assertEquals(futures.get(0).get(), "B",
                    "Future for first task returned wrong value.");

            Assert.assertTrue(futures.get(1).isDone(),
                    "Future for second task does not indicate it is done after invokeAll.");
            Assert.assertEquals(futures.get(1).get(1, TimeUnit.SECONDS), "C",
                    "Future for second task returned wrong value.");

            Assert.assertEquals(Label.get(), "timed-invokeAll-test-label-A",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
            Assert.assertEquals(Buffer.get().toString(), "timed-invokeAll-test-buffer-A",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
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
     * ManagedExecutor builder for one or more tasks that are submitted via the ManagedExecutor's
     * timed invokeAny operation. Thread context is captured at the point where invokeAny is
     * invoked, rather than upon creation of the executor or construction of the builder.
     */
    @Test
    public void timedInvokeAnyRunsTaskWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("timed-invokeAny-test-buffer-A"));
            Label.set("timed-invokeAny-test-label-A");

            Character result = executor.invokeAny(Arrays.<Callable<Character>>asList(
                    () -> {
                        Assert.assertEquals(Buffer.get().toString(), "timed-invokeAny-test-buffer-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Label.get(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("timed-invokeAny-test-label-B");
                        Buffer.set(new StringBuffer("timed-invokeAny-test-buffer-B"));
                        return 'b';
                    },
                    () -> {
                        Assert.assertEquals(Buffer.get().toString(), "timed-invokeAny-test-buffer-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Label.get(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("timed-invokeAny-test-label-C");
                        Buffer.set(new StringBuffer("invokeAny-test-buffer-C"));
                        return 'c';
                    }),
                    MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            Assert.assertTrue(Character.valueOf('b').equals(result) || Character.valueOf('c').equals(result),
                    "Result of invokeAny, " + result + ", does not match the result of either of the tasks.");

            Assert.assertEquals(Label.get(), "timed-invokeAny-test-label-A",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
            Assert.assertEquals(Buffer.get().toString(), "timed-invokeAny-test-buffer-A",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
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
     * ManagedExecutor builder for all tasks that are submitted via the ManagedExecutor's
     * untimed invokeAll operation. Thread context is captured at the point where invokeAll is
     * invoked, rather than upon creation of the executor or construction of the builder.
     */
    @Test
    public void untimedInvokeAllRunsTasksWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(1)
                .propagated(Label.CONTEXT_NAME)
                .build();

        // Verify that maxAsync=1 is enforced by recording the thread id upon which
        // the managed executor runs tasks asynchronous to the requesting thread.
        // If this value is ever found to be non-zero when set to a new thread id,
        // this indicates that multiple tasks are running asynchronously to the
        // requesting thread, which is a violation of maxAsync=1.
        AtomicLong asyncThreadIdRef = new AtomicLong();
        long testThreadId = Thread.currentThread().getId();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("untimed-invokeAll-test-buffer-A"));
            Label.set("untimed-invokeAll-test-label-A");

            List<Future<Integer>> futures = executor.invokeAll(Arrays.<Callable<Integer>>asList(
                    () -> {
                        long threadId = Thread.currentThread().getId();
                        if (threadId != testThreadId) {
                            Assert.assertEquals(asyncThreadIdRef.getAndSet(threadId), 0L,
                                    "Thread ID indicates that ManagedExecutor invokeAll operation exceeded maxAsync of 1.");
                        }

                        Assert.assertEquals(Label.get(), "untimed-invokeAll-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAll-test-label-B");
                        Buffer.set(new StringBuffer("untimed-invokeAll-test-buffer-B"));
                        asyncThreadIdRef.compareAndSet(threadId, 0L);
                        return 66;
                    },
                    () -> {
                        long threadId = Thread.currentThread().getId();
                        if (threadId != testThreadId) {
                            Assert.assertEquals(asyncThreadIdRef.getAndSet(threadId), 0L,
                                    "Thread ID indicates that ManagedExecutor invokeAll operation exceeded maxAsync of 1.");
                        }

                        Assert.assertEquals(Label.get(), "untimed-invokeAll-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAll-test-label-C");
                        Buffer.set(new StringBuffer("uninvokeAll-test-buffer-C"));
                        asyncThreadIdRef.compareAndSet(threadId, 0L);
                        return 67;
                    },
                    () -> {
                        long threadId = Thread.currentThread().getId();
                        if (threadId != testThreadId) {
                            Assert.assertEquals(asyncThreadIdRef.getAndSet(threadId), 0L,
                                    "Thread ID indicates that ManagedExecutor invokeAll operation exceeded maxAsync of 1.");
                        }

                        Assert.assertEquals(Label.get(), "untimed-invokeAll-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAll-test-label-D");
                        Buffer.set(new StringBuffer("untimed-invokeAll-test-buffer-D"));
                        asyncThreadIdRef.compareAndSet(threadId, 0L);
                        return 68;
                    },
                    () -> {
                        long threadId = Thread.currentThread().getId();
                        if (threadId != testThreadId) {
                            Assert.assertEquals(asyncThreadIdRef.getAndSet(threadId), 0L,
                                    "Thread ID indicates that ManagedExecutor invokeAll operation exceeded maxAsync of 1.");
                        }

                        Assert.assertEquals(Label.get(), "untimed-invokeAll-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAll-test-label-E");
                        Buffer.set(new StringBuffer("untimed-invokeAll-test-buffer-E"));
                        asyncThreadIdRef.compareAndSet(threadId, 0L);
                        return 69;
                    }),
                    MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            Assert.assertEquals(futures.size(), 4,
                    "Number of futures does not match the number of tasks. " + futures);

            Assert.assertTrue(futures.get(0).isDone(),
                    "Future for first task does not indicate it is done after invokeAll.");
            Assert.assertEquals(futures.get(0).get(), Integer.valueOf(66),
                    "Future for first task returned wrong value.");

            Assert.assertTrue(futures.get(1).isDone(),
                    "Future for second task does not indicate it is done after invokeAll.");
            Assert.assertEquals(futures.get(1).get(1, TimeUnit.SECONDS), Integer.valueOf(67),
                    "Future for second task returned wrong value.");

            Assert.assertTrue(futures.get(2).isDone(),
                    "Future for third task does not indicate it is done after invokeAll.");
            Assert.assertEquals(futures.get(2).get(), Integer.valueOf(68),
                    "Future for third task returned wrong value.");

            Assert.assertTrue(futures.get(3).isDone(),
                    "Future for fourth task does not indicate it is done after invokeAll.");
            Assert.assertEquals(futures.get(3).get(), Integer.valueOf(69),
                    "Future for fourth task returned wrong value.");

            Assert.assertEquals(Label.get(), "untimed-invokeAll-test-label-A",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
            Assert.assertEquals(Buffer.get().toString(), "untimed-invokeAll-test-buffer-A",
                    "Previous context was not restored after context was cleared for managed executor tasks.");
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
     * ManagedExecutor builder for one or more tasks that are submitted via the ManagedExecutor's
     * untimed invokeAny operation. Thread context is captured at the point where invokeAny is
     * invoked, rather than upon creation of the executor or construction of the builder.
     */
    @Test
    public void untimedInvokeAnyRunsTasksWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(1)
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("untimed-invokeAny-test-buffer-A"));
            Label.set("untimed-invokeAny-test-label-A");

            String result = executor.invokeAny(Arrays.<Callable<String>>asList(
                    () -> {
                        Assert.assertEquals(Label.get(), "untimed-invokeAny-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAny-test-label-B");
                        Buffer.set(new StringBuffer("untimed-invokeAny-test-buffer-B"));
                        return "Bb";
                    },
                    () -> {
                        Assert.assertEquals(Label.get(), "untimed-invokeAny-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAny-test-label-C");
                        Buffer.set(new StringBuffer("uninvokeAny-test-buffer-C"));
                        return "Cc";
                    },
                    () -> {
                        Assert.assertEquals(Label.get(), "untimed-invokeAny-test-label-A",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Label.set("untimed-invokeAny-test-label-D");
                        Buffer.set(new StringBuffer("untimed-invokeAny-test-buffer-D"));
                        return "Dd";
                    }),
                    MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            Assert.assertTrue("Bb".equals(result) || "Cc".equals(result) || "Dd".equals(result),
                    "Result of invokeAny, " + result + ", does not match the result of any of the tasks.");

            Assert.assertEquals(Label.get(), "untimed-invokeAny-test-label-A",
                    "Previous context was not restored after context was propagated for managed executor tasks.");
            Assert.assertEquals(Buffer.get().toString(), "untimed-invokeAny-test-buffer-A",
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
