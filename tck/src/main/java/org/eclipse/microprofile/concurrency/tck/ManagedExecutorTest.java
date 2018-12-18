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

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

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
