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

import static org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider.THREAD_PRIORITY;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrency.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.concurrent.ThreadContext;
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

public class ThreadContextTest extends Arquillian {
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
        System.out.println("<<< END ThreadContextTest." + m.getName());
    }

    @BeforeClass
    public void before() {
        unmanagedThreads = Executors.newFixedThreadPool(5);
    }

    @BeforeMethod
    public void beforeMethod(Method m) {
        System.out.println(">>> BEGIN ThreadContextTest." + m.getName());
    }

    @Deployment
    public static WebArchive createDeployment() {
        // build a JAR that provides fake 'ThreadPriority' context type
        JavaArchive threadPriorityContextProvider = ShrinkWrap.create(JavaArchive.class, "threadPriorityContext.jar")
                .addPackage("org.eclipse.microprofile.concurrency.tck.contexts.priority.spi")
                .addAsServiceProvider(ThreadContextProvider.class, ThreadPriorityContextProvider.class);

        // build a JAR that provides two fake context types: 'Buffer' and 'Label'
        JavaArchive multiContextProvider = ShrinkWrap.create(JavaArchive.class, "bufferAndLabelContext.jar")
                .addPackages(true, "org.eclipse.microprofile.concurrency.tck.contexts.buffer")
                .addPackages(true, "org.eclipse.microprofile.concurrency.tck.contexts.label")
                .addAsServiceProvider(ThreadContextProvider.class, BufferContextProvider.class, LabelContextProvider.class);

        return ShrinkWrap.create(WebArchive.class, ThreadContextTest.class.getSimpleName() + ".war")
                .addClass(ThreadContextTest.class)
                .addAsLibraries(threadPriorityContextProvider, multiContextProvider);
    }

    @Test
    public void builderForThreadContextIsProvided() {
        Assert.assertNotNull(ThreadContext.builder(),
                "MicroProfile Concurrency implementation does not provide a ThreadContext builder.");
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualConsumer
     * method can be used to wrap a BiConsumer instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the BiConsumer accept method runs. This test case aligns with use case of
     * supplying a contextual BiConsumer to a completion stage that is otherwise not context-aware.
     */
    @Test
    public void contextualBiConsumerRunsWithContext() throws InterruptedException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("contextualBiConsumer-test-buffer");
            Label.set("contextualBiConsumer-test-label");

            // To avoid the possibility that CompletableFuture.get might cause the action to run
            // on the current thread, which would bypass the intent of testing context propagation,
            // use a countdown latch to independently wait for completion.
            CountDownLatch completed = new CountDownLatch(1);

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of MicroProfile Concurrency's ManagedExecutor.
            // This ensures we are testing that MicroProfile Concurrency's ThreadContext is
            // doing the work to propagate the context rather than getting it from a
            // ManagedExecutor.
            CompletableFuture<String> stage1a = CompletableFuture.supplyAsync(() -> "supplied-value-A");
            CompletableFuture<String> stage1b = CompletableFuture.supplyAsync(() -> "supplied-value-B");

            CompletableFuture<Void> stage2 = stage1a.thenAcceptBothAsync(stage1b,
                    bufferContext.contextualConsumer((a, b) -> {
                        Assert.assertEquals(a, "supplied-value-A",
                                "First value supplied to BiConsumer was lost or altered.");

                        Assert.assertEquals(b, "supplied-value-B",
                                "Second value supplied to BiConsumer was lost or altered.");

                        Assert.assertEquals(Buffer.get().toString(), "contextualBiConsumer-test-buffer",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Label.get(), "",
                                "Context type that is configured to be cleared was not cleared.");
                    }),
                    unmanagedThreads);

            stage2.whenComplete((unused, failure) -> completed.countDown());

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completable future did not finish in a reasonable amount of time.");

            // Force errors, if any, to be reported
            stage2.join();
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualFunction
     * method can be used to wrap a BiFunction instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the BiFunction apply method runs. This test case aligns with use case of
     * supplying a contextual BiFunction to a completion stage that is otherwise not context-aware.
     */
    @Test
    public void contextualBiFunctionRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("contextualBiFunction-test-buffer");
            Label.set("contextualBiFunction-test-label");

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of MicroProfile Concurrency's ManagedExecutor.
            // This ensures we are testing that MicroProfile Concurrency's ThreadContext is
            // doing the work to propagate the context rather than getting it from a
            // ManagedExecutor.
            CompletableFuture<Integer> stage1a = new CompletableFuture<Integer>();
            CompletableFuture<Integer> stage1b = new CompletableFuture<Integer>();

            CompletableFuture<Integer> stage2 = stage1a.thenCombine(stage1b,
                    labelContext.contextualFunction((a, b) -> {
                        Assert.assertEquals(a, Integer.valueOf(10),
                                "First value supplied to BiFunction was lost or altered.");

                        Assert.assertEquals(b, Integer.valueOf(20),
                                "Second value supplied to BiFunction was lost or altered.");

                        Assert.assertEquals(Label.get(), "contextualBiFunction-test-label",
                                "Context type was not propagated to contextual action.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        return a + b;
                    }));

            Future<Integer> future = unmanagedThreads.submit(() -> {
                stage1a.complete(10);
                stage1b.complete(20);
                return stage2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);
            });

            Assert.assertEquals(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(30),
                    "Result of BiFunction was lost or altered.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualCallable
     * method can be used to wrap a Callable instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Callable call method runs. This test case aligns with use case of
     * supplying a contextual Callable to an unmanaged executor that is otherwise not context-aware.
     */
    @Test
    public void contextualCallableRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext priorityContext = ThreadContext.builder()
                .propagated(THREAD_PRIORITY)
                .build();

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 4 ? 3 : 4;
            Thread.currentThread().setPriority(newPriority);
            Buffer.get().append("contextualCallable-test-buffer");
            Label.set("contextualCallable-test-label");

            Future<Integer> future = unmanagedThreads.submit(priorityContext.contextualCallable(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that is configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "",
                        "Context type (Label) that is configured to be cleared was not cleared.");

                return Thread.currentThread().getPriority();
            }));

            Assert.assertEquals(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(newPriority),
                    "Callable returned incorrect value.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualConsumer
     * method can be used to wrap a Consumer instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Consumer accept method runs. This test case aligns with use case of
     * supplying a contextual Consumer to a completion stage that is otherwise not context-aware.
     */
    @Test
    public void contextualConsumerRunsWithContext() throws InterruptedException {
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("contextualConsumer-test-buffer");
            Label.set("contextualConsumer-test-label");

            // To avoid the possibility that CompletableFuture.get might cause the action to run
            // on the current thread, which would bypass the intent of testing context propagation,
            // use a countdown latch to independently wait for completion.
            CountDownLatch completed = new CountDownLatch(1);

            // Similarly, the initial stage is left incomplete until after thenAccept adds the
            // action, to eliminate the possibility that this triggers the action to run inline.

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of MicroProfile Concurrency's ManagedExecutor.
            // This ensures we are testing that MicroProfile Concurrency's ThreadContext is
            // doing the work to propagate the context rather than getting it from a 
            // ManagedExecutor.
            CompletableFuture<String> stage1 = new CompletableFuture<String>();
            CompletableFuture<Void> stage2 = stage1
                    .thenApplyAsync(unused -> "supply-to-consumer", unmanagedThreads)
                    .thenAccept(labelContext.contextualConsumer(s -> {
                        Assert.assertEquals(s, "supply-to-consumer",
                                "Value supplied to Consumer was lost or altered.");

                        Assert.assertEquals(Buffer.get().toString(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        Assert.assertEquals(Label.get(), "contextualConsumer-test-label",
                                "Context type was not propagated to contextual action.");
                    }));

            stage1.complete("unblock");

            stage2.whenComplete((unused, failure) -> completed.countDown());

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completable future did not finish in a reasonable amount of time.");

            // Force errors, if any, to be reported
            stage2.join();
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualFunction
     * method can be used to wrap a Function instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Function apply method runs. This test case aligns with use case of
     * supplying a contextual Function to a completion stage that is otherwise not context-aware.
     */
    @Test
    public void contextualFunctionRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("contextualFunction-test-buffer");
            Label.set("contextualFunction-test-label");

            // Reusable contextual function
            Function<Long, Long> contextualFunction = bufferContext.contextualFunction(i -> {
                Buffer.get().append("-" + i);

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");
                
                return i * 2L;
            });

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of MicroProfile Concurrency's ManagedExecutor.
            // This ensures we are testing that MicroProfile Concurrency's ThreadContext is
            // doing the work to propagate the context rather than getting it from a
            // ManagedExecutor.
            CompletableFuture<Long> stage1 = new CompletableFuture<Long>();

            CompletableFuture<Long> stage2 = stage1
                    .thenApply(contextualFunction)
                    .thenApply(i -> i + 10)
                    .thenApply(contextualFunction);

            Future<Long> future = unmanagedThreads.submit(() -> {
                stage1.complete(75L);
                return stage2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);
            });

            Assert.assertEquals(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Long.valueOf(320),
                    "Result of Function was lost or altered.");

            // Verify updates written to the 'buffer' context from the contextual actions
            Assert.assertEquals(Buffer.get().toString(), "contextualFunction-test-buffer-75-160",
                    "Context not propagated or incorrectly propagated to contextualFunctions.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualRunnable
     * method can be used to wrap a Runnable instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Runnable run method runs. This test case aligns with use case of
     * supplying a contextual Runnable to a completion stage that is otherwise not context-aware.
     */
    @Test
    public void contextualRunnableRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext priorityAndBufferContext = ThreadContext.builder()
                .propagated(THREAD_PRIORITY, Buffer.CONTEXT_NAME)
                .build();

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 2 ? 1 : 2;
            Thread.currentThread().setPriority(newPriority);
            Buffer.get().append("contextualRunnable-test-buffer");
            Label.set("contextualRunnable-test-label");

            // Reusable contextual Runnable
            Runnable contextualRunnable = priorityAndBufferContext.contextualRunnable(() -> {
                int priority = Thread.currentThread().getPriority();

                Buffer.get().append("-" + priority);

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");
            });

            Thread.currentThread().setPriority(originalPriority);

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of MicroProfile Concurrency's ManagedExecutor.
            // This ensures we are testing that MicroProfile Concurrency's ThreadContext is
            // doing the work to propagate the context rather than getting it from a
            // ManagedExecutor.
            CompletableFuture<Void> stage1 = new CompletableFuture<Void>();

            CompletableFuture<Void> stage2 = stage1
                    .thenRun(contextualRunnable)
                    .thenRun(() -> Buffer.get().append("-non-contextual-runnable"))
                    .thenRun(contextualRunnable);

            Future<Void> future = unmanagedThreads.submit(() -> {
                stage1.complete(null);
                stage2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);
                return null;
            });

            future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Verify updates written to the 'buffer' context from the contextual actions
            Assert.assertEquals(Buffer.get().toString(), "contextualRunnable-test-buffer-" + newPriority + "-" + newPriority,
                    "Context not propagated or incorrectly propagated to contextualFunctions.");
        }
        finally {
            // Restore original values
            Thread.currentThread().setPriority(originalPriority);
            Buffer.set(null);
            Label.set(null);
        }
    }
    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's contextualSupplier
     * method can be used to wrap a Supplier instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Supplier get method runs. This test case aligns with use case of
     * supplying a contextual Supplier to a completion stage that is otherwise not context-aware.
     */
    @Test
    public void contextualSupplierRunsWithContext() throws InterruptedException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged(ThreadContext.APPLICATION)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("contextualSupplier-test-buffer");
            Label.set("contextualSupplier-test-label");

            // To avoid the possibility that CompletableFuture.get might cause the action to run
            // on the current thread, which would bypass the intent of testing context propagation,
            // use a countdown latch to independently wait for completion.
            CountDownLatch completed = new CountDownLatch(1);

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of MicroProfile Concurrency's ManagedExecutor.
            // This ensures we are testing that MicroProfile Concurrency's ThreadContext is
            // doing the work to propagate the context rather than getting it from a 
            // ManagedExecutor.
            CompletableFuture<String> stage1 = CompletableFuture.supplyAsync(
                    bufferContext.contextualSupplier(() -> {
                        Assert.assertEquals(Label.get(), "",
                                "Context type that is configured to be cleared was not cleared.");

                        String bufferContents = Buffer.get().toString();
                        Assert.assertEquals(bufferContents, "contextualSupplier-test-buffer",
                                "Context type was not propagated to contextual action.");
                        return bufferContents;
                    }));

            stage1.whenComplete((unused, failure) -> completed.countDown());

            Assert.assertTrue(completed.await(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Completable future did not finish in a reasonable amount of time.");

            // Force errors, if any, to be reported
            String result = stage1.join();

            Assert.assertEquals(result, "contextualSupplier-test-buffer",
                    "Supplier result was lost or altered.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the MicroProfile Concurrency implementation finds third-party thread context providers
     * that are made available to the ServiceLoader, allows their configuration via the ThreadContext builder,
     * and correctly captures & propagates or clears these thread context types per the builder configuration.
     * Subsequently verify that the MicroProfile Concurrency implementation properly restores thread context
     * after the contextual action completes.
     */
    @Test
    public void thirdPartyContextProvidersAreIncludedInThreadContext() {
        ThreadContext labelAndPriorityContext = ThreadContext.builder()
                .propagated(THREAD_PRIORITY, Label.CONTEXT_NAME)
                .cleared(Buffer.CONTEXT_NAME)
                .unchanged(ThreadContext.ALL_REMAINING)
                .build();

        int originalPriority = Thread.currentThread().getPriority();
        int priorityA = originalPriority == 3 ? 2 : 3; // a non-default value
        int priorityB = priorityA - 1; // a different non-default value
        try {
            // Set non-default values
            Buffer.get().append("test-buffer-content-A");
            Label.set("test-label-A");
            Thread.currentThread().setPriority(priorityA);

            Supplier<Integer> contextualSupplier = labelAndPriorityContext.contextualSupplier(() -> {
                Assert.assertEquals(Buffer.get().toString(), "", "Context type that is configured to be cleared was not cleared.");
                Assert.assertEquals(Label.get(), "test-label-A", "Context type was not propagated to contextual action.");
                return Thread.currentThread().getPriority();
            });

            // Alter the values again
            Buffer.get().append("-and-B");
            Label.set("test-label-B");
            Thread.currentThread().setPriority(priorityB);

            // The contextual action runs with previously captured Label/ThreadPriority context, and with cleared Buffer context
            int priority = contextualSupplier.get();

            Assert.assertEquals(priority, priorityA, "Context type was not propagated to contextual action.");

            // The contextual action and its associated thread context snapshot is reusable
            priority = contextualSupplier.get();

            Assert.assertEquals(priority, priorityA, "Context type was not propagated to contextual action.");

            // Has context been properly restored after the contextual operation(s)?
            Assert.assertEquals(Buffer.get().toString(), "test-buffer-content-A-and-B",
                    "Previous context was not restored after context was cleared for contextual action.");
            Assert.assertEquals(Label.get(), "test-label-B",
                    "Previous context (Label) was not restored after context was propagated for contextual action.");
            Assert.assertEquals(Thread.currentThread().getPriority(), priorityB,
                    "Previous context (ThreadPriority) was not restored after context was propagated for contextual action.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }
    
    /**
     * Verify the MicroProfile Concurrency implementation of propagate(), cleared(), and unchanged()
     * for ThreadContext.Builder.
     */
    @Test
    public void contextControlsForThreadContextBuilder() throws InterruptedException, ExecutionException, TimeoutException {
    	ThreadContext bufferContext = ThreadContext.builder()
    			.propagated(Buffer.CONTEXT_NAME)
    			.cleared(Label.CONTEXT_NAME)
    			.unchanged(THREAD_PRIORITY)
    			.build();

    	try {
    		ThreadContext.builder()
    		.propagated(Buffer.CONTEXT_NAME)
    		.cleared(Label.CONTEXT_NAME, Buffer.CONTEXT_NAME)
    		.unchanged(THREAD_PRIORITY)
    		.build();
    		Assert.fail("ThreadContext.Builder.build() should throw an IllegalStateException for set overlap between propagated and cleared");
    	} catch (IllegalStateException ISE) {
    		//expected.
    	}

    	int originalPriority = Thread.currentThread().getPriority();
    	try {
    		// Set non-default values
    		int newPriority = originalPriority == 4 ? 3 : 4;
    		Buffer.get().append("contextControls-test-buffer-A");
    		Label.set("contextControls-test-label-A");

    		Callable<Integer> callable = bufferContext.contextualCallable(() -> {
    			Assert.assertEquals(Buffer.get().toString(), "contextControls-test-buffer-A-B",
    					"Context type was not propagated to contextual action.");

    			Buffer.get().append("-C");

    			Assert.assertEquals(Label.get(), "",
    					"Context type that is configured to be cleared was not cleared.");

    			Label.set("contextControls-test-label-C");

    			return Thread.currentThread().getPriority();
    		});

    		Buffer.get().append("-B");
    		Label.set("contextControls-test-label-B");

    		Future<Integer> future = unmanagedThreads.submit(() -> {
    			try {
    				Buffer.get().append("unpropagated-buffer");
        			Label.set("unpropagated-label");
        			Thread.currentThread().setPriority(newPriority);
        			
        			Integer returnedPriority = callable.call();
        			
            		Assert.assertEquals(Buffer.get().toString(), "unpropagated-buffer",
            				"Context type was not left unchanged by contextual action.");
            		
            		Assert.assertEquals(Label.get(), "unpropagated-label",
            				"Context type was not left unchanged by contextual action.");
            		
            		return returnedPriority;
    			}
    	    	finally {
    	    		// Restore original values
    	    		Buffer.set(null);
    	    		Label.set(null);
    	    		Thread.currentThread().setPriority(originalPriority);
    	    	}
    		});

    		Assert.assertEquals(future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(newPriority),
    				"Callable returned incorrect value.");

    		Assert.assertEquals(Buffer.get().toString(), "contextControls-test-buffer-A-B-C",
    				"Context type was not propagated to contextual action.");
    	}
    	finally {
    		// Restore original values
    		Buffer.set(null);
    		Label.set(null);
    		Thread.currentThread().setPriority(originalPriority);
    	}
    }

    /**
     * Verify that the MicroProfile Concurrency ThreadContext implementation's currentContextExecutor
     * method can be used to create an Executor instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied to the thread where the Executor's execute method runs. This test case aligns with use 
     * case of supplying a contextual Executor to a thread that is otherwise not context-aware.
     */
    @Test
    public void currentContextExecutorRunsWithContext() throws InterruptedException, ExecutionException, TimeoutException {
    	ThreadContext bufferContext = ThreadContext.builder()
    			.propagated(Buffer.CONTEXT_NAME)
    			.build();

    	try {
    		// Set non-default values
    		Buffer.get().append("currentContextExecutor-test-buffer-A");
    		Label.set("currentContextExecutor-test-label-A");

    		// Reusable contextual Executor
    		Executor contextSnapshot = bufferContext.currentContextExecutor();

    		Buffer.get().append("-B");
    		Label.set("currentContextExecutor-test-label-B");

    		// Run contextSnapshot.execute from another thread.
    		Future<Void> future = unmanagedThreads.submit(() -> {
    			try {
        			Buffer.get().append("currentContextExecutor-test-buffer-C");
        			Label.set("currentContextExecutor-test-label-C");
        			contextSnapshot.execute(() -> {
        				Assert.assertEquals(Buffer.get().toString(), "currentContextExecutor-test-buffer-A-B",
        						"Context type was not propagated to contextual action.");
        				Buffer.get().append("-D");

        				Assert.assertEquals(Label.get(), "",
        						"Context type that is configured to be cleared was not cleared.");
        				Label.set("currentContextExecutor-test-label-D");
        			});

        			// Execute should not have changed the current thread's context
        			Assert.assertEquals(Buffer.get().toString(), "currentContextExecutor-test-buffer-C",
        					"Existing context was altered by a contextual Executor.execute().");

        			Assert.assertEquals(Label.get(), "currentContextExecutor-test-label-C",
        					"Existing context was altered by a contextual Executor.execute().");
        			return null;
    			}
    	    	finally {
    	    		// Restore original values
    	    		Buffer.set(null);
    	    		Label.set(null);
    	    	}
    		});

    		future.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);

    		// Execute should not have changed the current thread's context
    		Assert.assertEquals(Buffer.get().toString(), "currentContextExecutor-test-buffer-A-B-D",
    				"Existing context was altered by a contextual Executor.execute().");

    		Assert.assertEquals(Label.get(), "currentContextExecutor-test-label-B",
    				"Existing context was altered by a contextual Executor.execute().");

    		// Run contextSnapshot.execute after the context has changed.
    		contextSnapshot.execute(() -> {
    			Assert.assertEquals(Buffer.get().toString(), "currentContextExecutor-test-buffer-A-B-D",
    					"Context type was not propagated to contextual action.");
    			Buffer.get().append("-E");

    			Assert.assertEquals(Label.get(), "",
    					"Context type that is configured to be cleared was not cleared.");
    			Label.set("currentContextExecutor-test-label-E");
    		});

    		// Execute should not have changed the current thread's context
    		Assert.assertEquals(Buffer.get().toString(), "currentContextExecutor-test-buffer-A-B-D-E",
    				"Existing context was altered by a contextual Executor.execute().");

    		Assert.assertEquals(Label.get(), "currentContextExecutor-test-label-B",
    				"Existing context was altered by a contextual Executor.execute().");
    	}
    	finally {
    		// Restore original values
    		Buffer.set(null);
    		Label.set(null);
    	}
    }
}
