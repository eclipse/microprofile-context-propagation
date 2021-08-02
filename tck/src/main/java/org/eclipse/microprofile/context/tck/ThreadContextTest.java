/*
 * Copyright (c) 2018,2021 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.context.tck;

import static org.eclipse.microprofile.context.tck.contexts.priority.spi.ThreadPriorityContextProvider.THREAD_PRIORITY;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.context.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.context.tck.contexts.label.Label;
import org.eclipse.microprofile.context.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.context.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.context.spi.ContextManager;
import org.eclipse.microprofile.context.spi.ContextManagerProvider;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.SkipException;
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
        // build a JAR that provides fake 'ThreadPriority' context type
        JavaArchive threadPriorityContextProvider = ShrinkWrap.create(JavaArchive.class, "threadPriorityContext.jar")
                .addPackage("org.eclipse.microprofile.context.tck.contexts.priority.spi")
                .addAsServiceProvider(ThreadContextProvider.class, ThreadPriorityContextProvider.class);

        // build a JAR that provides two fake context types: 'Buffer' and 'Label'
        JavaArchive multiContextProvider = ShrinkWrap.create(JavaArchive.class, "bufferAndLabelContext.jar")
                .addPackages(true, "org.eclipse.microprofile.context.tck.contexts.buffer")
                .addPackages(true, "org.eclipse.microprofile.context.tck.contexts.label")
                .addAsServiceProvider(ThreadContextProvider.class, BufferContextProvider.class, LabelContextProvider.class);

        return ShrinkWrap.create(WebArchive.class, ThreadContextTest.class.getSimpleName() + ".war")
                .addClasses(ThreadContextTest.class, TckThread.class, TckThreadFactory.class)
                .addAsLibraries(threadPriorityContextProvider, multiContextProvider);
    }

    @Test
    public void builderForThreadContextIsProvided() {
        Assert.assertNotNull(ThreadContext.builder(),
                "MicroProfile Context Propagation implementation does not provide a ThreadContext builder.");
    }

    /**
     * Verify that if JTA transactions are supported, then configuring cleared=TRANSACTION
     * results in the active transaction being suspended before running a task and resumed afterward,
     * such that a task can use its own transactions if so desired.
     *
     * The test tries to get hold of {@code UserTransaction} via JNDI and via CDI.
     * Should neither work, it still doesn't throw exception but instead returns.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void clearTransactionContextJTA() throws Exception {
        // try to get UserTransaction via JNDI
        UserTransaction txFromJNDI = null;
        try {
            txFromJNDI = InitialContext.doLookup("java:comp/UserTransaction");
        }
        catch (NamingException x) {
            // JTA UserTransaction not available in JNDI
        }

        // try to get UserTransaction via CDI
        UserTransaction txFromCDI = null;
        try {
            CDI<Object> cdi = CDI.current();
            Instance<UserTransaction> transactionInstance = cdi.select(UserTransaction.class);
            if (transactionInstance.isResolvable()) {
                // UserTransaction available via CDI
                txFromCDI = transactionInstance.get();
            } else {
                System.out.println("CDI implementation is present, but UserTransaction cannot be retrieved.");
            }
        }
        catch (IllegalStateException x) {
            System.out.println("CDI implementation not present, cannot retrieve UserTransaction from CDI." + x);
        }

        UserTransaction tx = txFromJNDI == null ? txFromCDI : txFromJNDI;
        if (tx == null) {
            System.out.println("Skipping test clearTransactionContextJTA. JTA transactions are not supported.");
            return;
        }

        ThreadContext threadContext = ThreadContext.builder()
                .cleared(ThreadContext.TRANSACTION)
                .unchanged()
                .propagated(ThreadContext.ALL_REMAINING)
                .build();

        Callable<String> taskWithNewTransaction = threadContext.contextualCallable(() -> {
            Assert.assertEquals(tx.getStatus(), Status.STATUS_NO_TRANSACTION,
                    "Transaction status should indicate no transaction is active on thread.");

            tx.begin();
            tx.commit();

            return "SUCCESS";
        });

        tx.begin();
        try {
            Assert.assertEquals(taskWithNewTransaction.call(), "SUCCESS");

            Assert.assertEquals(tx.getStatus(), Status.STATUS_ACTIVE,
                    "Transaction status should indicate active transaction on thread.");
        }
        finally {
            tx.commit();
        }
    }

    /**
     * Verify that the ThreadContext implementation clears context
     * types that are not configured under propagated, unchanged, or cleared.
     *
     * @throws Exception indicates test failure 
     */
    @Test
    public void clearUnspecifiedContexts() throws Exception {
        ThreadContext threadContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged(Label.CONTEXT_NAME)
                .cleared()
                .build();
        
        int originalPriority = Thread.currentThread().getPriority();     
        try {
            // Set non-default values
            int newPriority = originalPriority == 3 ? 2 : 3;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("clearUnspecifiedContexts-test-buffer-A"));
            Label.set("clearUnspecifiedContexts-test-label-A");

            Callable<Integer> callable = threadContext.contextualCallable(() -> {
                    Assert.assertEquals(Buffer.get().toString(), "clearUnspecifiedContexts-test-buffer-A",
                            "Context type was not propagated to contextual action.");

                    Assert.assertEquals(Label.get(), "clearUnspecifiedContexts-test-label-C",
                            "Context type was not left unchanged by contextual action.");

                    Buffer.set(new StringBuffer("clearUnspecifiedContexts-test-buffer-B"));
                    Label.set("clearUnspecifiedContexts-test-label-B");

                    return Thread.currentThread().getPriority();
            });

            Buffer.set(new StringBuffer("clearUnspecifiedContexts-test-buffer-C"));
            Label.set("clearUnspecifiedContexts-test-label-C");

            Assert.assertEquals(callable.call(), Integer.valueOf(Thread.NORM_PRIORITY),
                    "Context type that remained unspecified was not cleared by default.");
            
            Assert.assertEquals(Buffer.get().toString(), "clearUnspecifiedContexts-test-buffer-C",
                    "Previous context (Buffer) was not restored after context was propagated for contextual action.");
            
            Assert.assertEquals(Label.get(), "clearUnspecifiedContexts-test-label-B",
                    "Context type was not left unchanged by contextual action.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that the ThreadContext's contextualConsumer
     * method can be used to wrap a BiConsumer instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the BiConsumer accept method runs. This test case aligns with use case of
     * supplying a contextual BiConsumer to a completion stage that is otherwise not context-aware.
     *
     * @throws InterruptedException indicates test failure
     */
    @Test
    public void contextualBiConsumerRunsWithContext() throws InterruptedException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged()
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
            // propagation guarantees of ManagedExecutor.
            // This ensures we are testing that ThreadContext is
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
     * Verify that the ThreadContext's contextualFunction
     * method can be used to wrap a BiFunction instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the BiFunction apply method runs. This test case aligns with use case of
     * supplying a contextual BiFunction to a completion stage that is otherwise not context-aware.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void contextualBiFunctionRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            // Set non-default values
            Buffer.get().append("contextualBiFunction-test-buffer");
            Label.set("contextualBiFunction-test-label");

            // CompletableFuture from Java SE is intentionally used here to avoid the context
            // propagation guarantees of ManagedExecutor.
            // This ensures we are testing that ThreadContext is
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
     * Verify that the ThreadContext's contextualCallable
     * method can be used to wrap a Callable instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Callable call method runs. This test case aligns with use case of
     * supplying a contextual Callable to an unmanaged executor that is otherwise not context-aware.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void contextualCallableRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext priorityContext = ThreadContext.builder()
                .propagated(THREAD_PRIORITY)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
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
     * Verify that the ThreadContext's contextualConsumer
     * method can be used to wrap a Consumer instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Consumer accept method runs. This test case aligns with use case of
     * supplying a contextual Consumer to a completion stage that is otherwise not context-aware.
     *
     * @throws InterruptedException indicates test failure
     */
    @Test
    public void contextualConsumerRunsWithContext() throws InterruptedException {
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
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
            // propagation guarantees of ManagedExecutor.
            // This ensures we are testing that ThreadContext is
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
     * Verify that the ThreadContext's contextualFunction
     * method can be used to wrap a Function instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Function apply method runs. This test case aligns with use case of
     * supplying a contextual Function to a completion stage that is otherwise not context-aware.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void contextualFunctionRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
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
            // propagation guarantees of ManagedExecutor.
            // This ensures we are testing that ThreadContext is
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
     * Verify that the ThreadContext's contextualRunnable
     * method can be used to wrap a Runnable instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Runnable run method runs. This test case aligns with use case of
     * supplying a contextual Runnable to a completion stage that is otherwise not context-aware.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void contextualRunnableRunsWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext priorityAndBufferContext = ThreadContext.builder()
                .propagated(THREAD_PRIORITY, Buffer.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
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
            // propagation guarantees of ManagedExecutor.
            // This ensures we are testing that ThreadContext is
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
     * Verify that the ThreadContext's contextualSupplier
     * method can be used to wrap a Supplier instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied when the Supplier get method runs. This test case aligns with use case of
     * supplying a contextual Supplier to a completion stage that is otherwise not context-aware.
     *
     * @throws InterruptedException indicates test failure
     */
    @Test
    public void contextualSupplierRunsWithContext() throws InterruptedException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged(ThreadContext.APPLICATION)
                .cleared(ThreadContext.ALL_REMAINING)
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
            // propagation guarantees of ManagedExecutor.
            // This ensures we are testing that ThreadContext is
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
     * Verify that the ThreadContext.Builder can be used to create multiple ThreadContexts with 
     * different configured contexts.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void reuseThreadContextBuilder() throws Exception {
        ThreadContext.Builder builder = ThreadContext.builder()
                .propagated()
                .cleared(Buffer.CONTEXT_NAME, THREAD_PRIORITY)
                .unchanged();
        
        ThreadContext clearingContext = builder.build();
        
        ThreadContext propagatingContext = builder.propagated(Buffer.CONTEXT_NAME, THREAD_PRIORITY)
                .cleared()
                .build();

        int originalPriority = Thread.currentThread().getPriority();     
        try {
            // Set non-default values
            int newPriority = originalPriority == 3 ? 2 : 3;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("reuseBuilder-test-buffer-A"));

            Callable<Integer> clearedCallable = clearingContext.contextualCallable(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");
                
                Buffer.set(new StringBuffer("reuseBuilder-test-buffer-B"));
                
                return Thread.currentThread().getPriority();
            });

            Callable<Integer> propagatedCallable = propagatingContext.contextualCallable(() -> {
                Assert.assertEquals(Buffer.get().toString(), "reuseBuilder-test-buffer-A",
                        "Context type was not propagated to contextual action.");

                Buffer.set(new StringBuffer("reuseBuilder-test-buffer-C"));
                
                return Thread.currentThread().getPriority();
                
            });
            
            Buffer.set(new StringBuffer("reuseBuilder-test-buffer-D"));
            Thread.currentThread().setPriority(newPriority - 1);
            
            Assert.assertEquals(propagatedCallable.call(), Integer.valueOf(newPriority),
                    "Context type was not propagated to contextual action.");
            
            Assert.assertEquals(clearedCallable.call(), Integer.valueOf(Thread.NORM_PRIORITY),
                    "Context type that is configured to be cleared was not cleared.");

            Assert.assertEquals(Buffer.get().toString(), "reuseBuilder-test-buffer-D",
                    "Previous context (Buffer) was not restored after context was propagated for contextual action.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * It is optional to specify the set of unchanged context. In absence of any specified value
     * and if MicroProfile Config is not used to override the default, the unchanged context list
     * defaults to empty.
     */
    @Test
    public void unchangedContextListDefaultsToEmpty() {
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 3 ? 2 : 3; // a non-default value
        try {
            // Set non-default values
            Buffer.get().append("test-unchangedContextListDefaultsToEmpty-buffer-A");
            Label.set("test-unchangedContextListDefaultsToEmpty-label-A");
            Thread.currentThread().setPriority(newPriority);

            Runnable testPropagateLabelContextAndClearOthers = labelContext.contextualRunnable(() -> {
                Assert.assertEquals(Label.get(), "test-unchangedContextListDefaultsToEmpty-label-A",
                        "Context type was not propagated to contextual action.");
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that is defaulted to be cleared was not cleared.");
                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type (ThreadContext) that is defaulted to be cleared was not cleared.");
            });

            Label.set("test-unchangedContextListDefaultsToEmpty-label-B");

            testPropagateLabelContextAndClearOthers.run();

            // Has context been properly restored after the contextual operation?
            Assert.assertEquals(Buffer.get().toString(), "test-unchangedContextListDefaultsToEmpty-buffer-A",
                    "Previous context (Buffer) was not restored after context was cleared for contextual action.");
            Assert.assertEquals(Label.get(), "test-unchangedContextListDefaultsToEmpty-label-B",
                    "Previous context (Label) was not restored after context was propagated for contextual action.");
            Assert.assertEquals(Thread.currentThread().getPriority(), newPriority,
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
     * Verify that the MicroProfile Context Propagation implementation finds third-party thread context providers
     * that are made available to the ServiceLoader, allows their configuration via the ThreadContext builder,
     * and correctly captures and propagates or clears these thread context types per the builder configuration.
     * Subsequently verify that the MicroProfile Context Propagation implementation properly restores thread context
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
     * Verify that the ThreadContext's withContextCapture
     * method can be used to create a dependent CompletableFuture instance that completes when the
     * original stage completes and runs dependent stage actions with context that is captured
     * from the thread that creates the dependent stage.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void withContextCaptureDependentCompletableFuturesRunWithContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();
        ManagedExecutor bufferContextExecutor = ManagedExecutor.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        long testThreadId = Thread.currentThread().getId();
        try {
            // Set non-default values
            Buffer.get().append("withContextCapture-CompletableFuture-test-buffer");
            Label.set("withContextCapture-CompletableFuture-test-label-A");

            CompletableFuture<Integer> unmanagedStage1 = new CompletableFuture<Integer>();

            CompletableFuture<Integer> stage2 = labelContext.withContextCapture(unmanagedStage1);
            CompletableFuture<Integer> stage3 = stage2.thenApply(i -> {
                Assert.assertEquals(i, Integer.valueOf(1010),
                        "Value supplied to function does not match the value with which the dependent stage was completed.");

                Assert.assertEquals(Label.get(), "withContextCapture-CompletableFuture-test-label-A",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getId(), testThreadId,
                        "Completion stages created via withContextCapture must run on the test case's main thread " +
                        "because it both completes the original stage and requests the result.");

                Label.set("withContextCapture-CompletableFuture-test-label-B");

                return i * 2;
            });

            Label.set("withContextCapture-CompletableFuture-test-label-C");

            CompletableFuture<Integer> stage4 = stage3.thenApplyAsync(i -> {
                Assert.assertEquals(i, Integer.valueOf(2020),
                        "Incorrect value supplied to function.");

                Assert.assertEquals(Label.get(), "withContextCapture-CompletableFuture-test-label-C",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Label.set("withContextCapture-CompletableFuture-test-label-D");

                return i + i;
            }, bufferContextExecutor); // supplied executor runs the action, but does not determine context propagation

            // Original stage remains usable, but not having been created by a ManagedExecutor, does not make any
            // guarantees about context propagation
            CompletableFuture<Integer> unmanagedStage5 = unmanagedStage1.thenApply(i -> i / 2);

            Label.set("withContextCapture-CompletableFuture-test-label-E");

            unmanagedStage1.complete(1010);

            Assert.assertEquals(stage2.getNow(9090), Integer.valueOf(1010),
                    "Completion stage created by withContextCapture did not complete with same value as original stage.");

            Assert.assertEquals(stage3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(2020),
                    "Incorrect or missing result of completion stage.");

            Assert.assertEquals(stage4.join(), Integer.valueOf(4040),
                    "Incorrect or missing result of completion stage.");

            Assert.assertEquals(unmanagedStage5.get(), Integer.valueOf(505),
                    "Incorrect or missing result of completion stage.");

            // Has context been properly restored after the contextual operation(s)?
            Assert.assertEquals(Buffer.get().toString(), "withContextCapture-CompletableFuture-test-buffer",
                    "Previous context was not restored after context was cleared for contextual action.");
            Assert.assertEquals(Label.get(), "withContextCapture-CompletableFuture-test-label-E",
                    "Previous context was not restored after context was propagated for contextual action.");
        }
        finally {
            bufferContextExecutor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that the ThreadContext's withContextCapture
     * method can be used to create a dependent CompletionStage instance that completes when the
     * original stage completes and runs dependent stage actions with context that is captured
     * from the thread that creates the dependent stage.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void withContextCaptureDependentCompletionStagesRunWithContext() throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .unchanged()
                .build();
        ManagedExecutor labelContextExecutor = ManagedExecutor.builder()
                .propagated(Label.CONTEXT_NAME)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        long testThreadId = Thread.currentThread().getId();
        try {
            // Set non-default values
            StringBuffer buffer = new StringBuffer("withContextCapture-CompletionStage-test-buffer-A");
            Buffer.set(buffer);
            Label.set("withContextCapture-CompletionStage-test-label");

            CompletableFuture<Integer> unmanagedStage1 = new CompletableFuture<Integer>();

            CompletionStage<Integer> stage2 = bufferContext.withContextCapture((CompletionStage<Integer>) unmanagedStage1);

            if (stage2 instanceof CompletableFuture) {
                try {
                    ((CompletableFuture<Integer>) stage2).complete(4321);
                    Assert.fail("Must not be possible to forcibly complete the CompletionStage that is returned by the variant" +
                                "of withContextCapture that accepts and returns a CompletionStage rather than CompletableFuture.");
                }
                catch (UnsupportedOperationException x) {
                    // test passes - this matches behavior of Java SE minimalCompletionStage, which implements
                    // CompletableFuture, but rejects methods that perform completion.
                }
            }

            CompletionStage<Integer> stage3 = stage2.thenApply(i -> {
                Assert.assertEquals(i, Integer.valueOf(1234),
                        "Value supplied to function does not match the value with which the dependent stage was completed.");

                Assert.assertEquals(Buffer.get().toString(), "withContextCapture-CompletionStage-test-buffer-A",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Label.get().toString(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getId(), testThreadId,
                        "Completion stages created via withContextCapture must run on the test case's main thread " +
                        "because it both completes the original stage and requests the result.");

                Buffer.get().append("-stage3");
                Buffer.set(new StringBuffer("withContextCapture-CompletionStage-test-buffer-B"));

                return i * 2;
            });

            CompletionStage<Integer> stage4 = stage3.thenApplyAsync(i -> {
                Assert.assertEquals(i, Integer.valueOf(2468),
                        "Incorrect value supplied to function.");

                Assert.assertEquals(Buffer.get().toString(), "withContextCapture-CompletionStage-test-buffer-A-stage3",
                        "Context type was not propagated to contextual action.");

                Assert.assertEquals(Label.get(), "",
                        "Context type that is configured to be cleared was not cleared.");

                Buffer.get().append("-stage4");
                Buffer.set(new StringBuffer("withContextCapture-CompletionStage-test-buffer-D"));

                return i - 2345;
            }, labelContextExecutor); // supplied executor runs the action, but does not determine context propagation

            Buffer.set(new StringBuffer("withContextCapture-CompletionStage-test-buffer-E"));

            unmanagedStage1.complete(1234);

            CompletableFuture<Integer> cf4 = stage4.toCompletableFuture();

            Assert.assertEquals(cf4.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(123),
                    "Completion stage created by withContextCapture did not complete with same value as original stage.");

            Assert.assertEquals(buffer.toString(), "withContextCapture-CompletionStage-test-buffer-A-stage3-stage4");

            // Has context been properly restored after the contextual operation(s)?
            Assert.assertEquals(Buffer.get().toString(), "withContextCapture-CompletionStage-test-buffer-E",
                    "Previous context was not restored after context was propagated for contextual action.");
            Assert.assertEquals(Label.get(), "withContextCapture-CompletionStage-test-label",
                    "Previous context was not restored after context was cleared for contextual action.");
        }
        finally {
            labelContextExecutor.shutdownNow();
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that dependent stages created via withContextCapture can be completed independently
     * of the original stage.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     */
    @Test
    public void withContextCaptureDependentStageForcedCompletion() throws ExecutionException, InterruptedException {
        ThreadContext contextPropagator = ThreadContext.builder()
                .propagated()
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        CompletableFuture<String> stage1 = new CompletableFuture<String>();
        CompletableFuture<String> stage2 = contextPropagator.withContextCapture(stage1);

        Assert.assertTrue(stage2.complete("stage_2_done"),
                "It should be possible to complete a CompletableFuture created via withContextCapture without completing the original stage.");

        Assert.assertFalse(stage1.isDone(),
                "Completion of the dependent stage must not imply completion of the original stage.");

        Assert.assertTrue(stage1.complete("stage_1_done"),
                "It should be possible to complete the original stage with a different result after dependent stage was forcibly completed.");

        Assert.assertEquals(stage1.get(), "stage_1_done",
                "Completion stage result does not match the result with which it was forcibly completed.");

        Assert.assertEquals(stage2.get(), "stage_2_done",
                "Completion stage result does not match the result with which it was forcibly completed.");
    }

    /**
     * The withContextCapture method should be able to create multiple dependent stages
     * having a single parent stage, where each of the dependent stages propagates a
     * different set of thread context.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void withContextCaptureMultipleThreadContexts()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            Function<Character, String> getContext = c -> Buffer.get().append(c).append(Label.get()).toString();

            // Set non-default values
            Buffer.set(new StringBuffer("withContextCaptureMultipleThreadContexts-test-buffer-A"));
            Label.set("withContextCaptureMultipleThreadContexts-test-label-A");

            CompletableFuture<Character> unmanagedStage1 = new CompletableFuture<Character>();

            CompletableFuture<Character> stage2 = bufferContext.withContextCapture(unmanagedStage1);
            CompletableFuture<Character> stage3 = labelContext.withContextCapture(unmanagedStage1);

            CompletableFuture<String> stage4 = stage2.thenApply(getContext);
            CompletableFuture<String> stage5 = stage3.thenApply(getContext);

            // change context
            Buffer.set(new StringBuffer("withContextCaptureMultipleThreadContexts-test-buffer-B"));
            Label.set("withContextCaptureMultipleThreadContexts-test-label-B");

            unmanagedStage1.complete(';');

            Assert.assertEquals(stage4.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "withContextCaptureMultipleThreadContexts-test-buffer-A;",
                    "Context was incorrectly established on contextual function.");

            Assert.assertEquals(stage5.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), ";withContextCaptureMultipleThreadContexts-test-label-A",
                    "Context was incorrectly established on contextual function.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * The withContextCapture method should be able to create a dependent stage that is
     * associated with a thread context even if its parent stage is already associated with
     * a different thread context. Both stages must honor their respective thread context.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void withContextCaptureSwitchThreadContext()
            throws ExecutionException, InterruptedException, TimeoutException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();
        ThreadContext labelContext = ThreadContext.builder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        try {
            // Set non-default values
            Buffer.set(new StringBuffer("withContextCaptureSwitchThreadContext-test-buffer-A"));
            Label.set("withContextCaptureSwitchThreadContext-test-label-A");

            CompletableFuture<Character> unmanagedStage1 = new CompletableFuture<Character>();

            CompletableFuture<Character> stage2 = bufferContext.withContextCapture(unmanagedStage1);
            CompletableFuture<String> stage3 = stage2.thenApply(c -> Buffer.get().append(c).append(Label.get()).toString());

            // change context
            Buffer.set(new StringBuffer("withContextCaptureSwitchThreadContext-test-buffer-B"));
            Label.set("withContextCaptureSwitchThreadContext-test-label-B");

            CompletableFuture<String> stage4 = labelContext.withContextCapture(stage3);
            CompletableFuture<String> stage5 = stage4.thenApply(s -> s + ';' + Buffer.get().toString() + ';' + Label.get());

            // change context again
            Buffer.set(new StringBuffer("withContextCaptureSwitchThreadContext-test-buffer-C"));
            Label.set("withContextCaptureSwitchThreadContext-test-label-C");

            unmanagedStage1.complete(';');

            Assert.assertEquals(stage5.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "withContextCaptureSwitchThreadContext-test-buffer-A;;;withContextCaptureSwitchThreadContext-test-label-B",
                    "Context was incorrectly established on contextual function.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify the MicroProfile Context Propagation implementation of propagate(), cleared(), and unchanged()
     * for ThreadContext.Builder.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
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
        }
        catch (IllegalStateException ISE) {
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
     * Verify that the ThreadContext's currentContextExecutor
     * method can be used to create an Executor instance with the context that is captured from the
     * current thread per the configuration of the ThreadContext builder, and that the context is
     * applied to the thread where the Executor's execute method runs. This test case aligns with use 
     * case of supplying a contextual Executor to a thread that is otherwise not context-aware.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void currentContextExecutorRunsWithContext() throws InterruptedException, ExecutionException, TimeoutException {
        ThreadContext bufferContext = ThreadContext.builder()
                .propagated(Buffer.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
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

    /**
     * Verify that the presence of a default executor service allows contextualised CompletableFuture/CompletionStage
     * to run async actions on them, and that they are running on that executor service.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void withDefaultExecutorServiceContextCanInvokeAsyncActions()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContextManager.Builder builder = getContextManagerBuilderIfSupported();
        ExecutorService executorService = Executors.newSingleThreadExecutor(new TckThreadFactory());
        ContextManager contextManager = builder
                .withDefaultExecutorService(executorService)
                .addDiscoveredThreadContextProviders()
                .build();
        ThreadContext labelContext = contextManager.newThreadContextBuilder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();
        try {
            // Set non-default values
            Label.set("default-executor-service-context-test-label-A");
            
            CompletableFuture<String> noContextCF = new CompletableFuture<>();
            CompletableFuture<String> noContextCFStage1 = noContextCF.thenApplyAsync(res -> {
                Assert.assertEquals(Label.get(), "",
                        "Context type should not be propagated to non-contextual action.");

                return res;
            });

            CompletableFuture<String> contextCF = labelContext.withContextCapture(noContextCFStage1).thenApplyAsync(res -> {
                Assert.assertEquals(Label.get(), "default-executor-service-context-test-label-A",
                        "Context type should be propagated to contextual CompletableFuture.");
                
                Assert.assertTrue(Thread.currentThread() instanceof TckThread,
                        "Current thread should have been created by default executor service");
                return res;
            });

            CompletionStage<String> contextCS = labelContext.withContextCapture((CompletionStage<String>)noContextCFStage1).thenApplyAsync(res -> {
                Assert.assertEquals(Label.get(), "default-executor-service-context-test-label-A",
                        "Context type should be propagated to contextual CompletionStage.");
                
                Assert.assertTrue(Thread.currentThread() instanceof TckThread,
                        "Current thread should have been created by default executor service");
                return res;
            });

            noContextCF.complete("OK");
            contextCF.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);
            contextCS.toCompletableFuture().get(MAX_WAIT_NS, TimeUnit.NANOSECONDS);
        }
        finally {
            // Restore original values
            Label.set(null);
            executorService.shutdownNow();
        }
    }

    static ContextManager.Builder getContextManagerBuilderIfSupported() {
        try {
            return ContextManagerProvider
                .instance()
                .getContextManagerBuilder();
        } catch(UnsupportedOperationException x) {
            // optional operation: skip
            throw new SkipException("getContextManagerBuilder not supported", x);
        }
    }

    /**
     * Verify that the lack of a default executor service bans contextualised CompletableFuture/CompletionStage
     * from running async actions on them.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void withoutDefaultExecutorServiceContextCannotInvokeAsyncActions()
            throws ExecutionException, InterruptedException, TimeoutException {
        ContextManager contextManager;
        boolean noExecutorService = false;
        try {
            contextManager = getContextManagerBuilderIfSupported()
                    .addDiscoveredThreadContextProviders()
                    .withDefaultExecutorService(null)
                    .build();
            noExecutorService = true;
        } catch (SkipException x) {
            // not supported, use the default but we don't know if it will support async methods
            contextManager = ContextManagerProvider.instance().getContextManager();
        }
        ThreadContext labelContext = contextManager.newThreadContextBuilder()
                .propagated(Label.CONTEXT_NAME)
                .unchanged()
                .cleared(ThreadContext.ALL_REMAINING)
                .build();
        try {
            // Set non-default values
            Label.set("default-executor-service-context-test-label-A");
            
            CompletableFuture<String> noContextCF = new CompletableFuture<>();
            CompletableFuture<String> noContextCFStage1 = noContextCF.thenApplyAsync(res -> {
                Assert.assertEquals(Label.get(), "",
                        "Context type should not be propagated to non-contextual action.");

                return res;
            });

            try {
                labelContext.withContextCapture(noContextCFStage1).thenApplyAsync(res -> res);
                // if we were able to set an empty executor service, this must throw, otherwise
                // it's implementation-dependent
                if(noExecutorService) {
                    Assert.fail("Async action should not be allowed without a default executor service");
                }
            } catch (UnsupportedOperationException x) {
                // this can happen if we have no executor service, because noExecutorService is true, or
                // because the implementation doesn't have one
            }

            try {
                labelContext.withContextCapture((CompletionStage<String>)noContextCFStage1).thenApplyAsync(res -> res);
                // if we were able to set an empty executor service, this must throw, otherwise
                // it's implementation-dependent
                if(noExecutorService) {
                    Assert.fail("Async action should not be allowed without a default executor service");
                }
            } catch (UnsupportedOperationException x) {
                // this can happen if we have no executor service, because noExecutorService is true, or
                // because the implementation doesn't have one
            }
        }
        finally {
            // Restore original values
            Label.set(null);
        }
    }
}
