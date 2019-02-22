/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.concurrency.tck.cdi;

import static org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider.THREAD_PRIORITY;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.*;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.ThreadContextConfig;
import org.testng.Assert;

@ApplicationScoped
public class CDIBean {
    
    static final long MAX_WAIT_SEC = 120;

    @Inject
    ManagedExecutor exec;

    @Inject
    @NamedInstance("maxQueued3")
    @ManagedExecutorConfig(maxAsync = 1, maxQueued = 3)
    ManagedExecutor maxQueued3;

    @Inject
    @NamedInstance("appProduced")
    ManagedExecutor appProduced;

    @Inject
    @NamedInstance("appProduced_injected")
    ManagedExecutor appProduced_injected;

    @Inject
    ManagedExecutor throwAway1;

    @Inject
    ManagedExecutor throwAway2;

    @Produces
    @ApplicationScoped
    @NamedInstance("appProduced")
    public ManagedExecutor createExec() {
        return ManagedExecutor.builder().build();
    }

    @Produces
    @ApplicationScoped
    @NamedInstance("appProduced_injected")
    public ManagedExecutor createExecInjected(@ManagedExecutorConfig(maxAsync = 1, maxQueued = 3) ManagedExecutor exec) {
        return exec;
    }

    @Inject
    ThreadContext defaultContext;
   
    @Inject
    @ThreadContextConfig
    ThreadContext defaultContextWithEmptyAnno;

    @Inject
    @ThreadContextConfig(propagated = Buffer.CONTEXT_NAME, cleared = Label.CONTEXT_NAME, unchanged = ThreadContext.ALL_REMAINING)
    ThreadContext bufferContext;

    @Inject
    @ThreadContextConfig(propagated = Label.CONTEXT_NAME, cleared = ThreadContext.ALL_REMAINING, unchanged = Buffer.CONTEXT_NAME)
    ThreadContext labelContext;

    @Produces
    @ApplicationScoped
    @NamedInstance("labelContextPropagator")
    ThreadContext labelContextPropagator1 = ThreadContext.builder().propagated(Label.CONTEXT_NAME).cleared(ThreadContext.ALL_REMAINING).build();

    @Inject
    @NamedInstance("labelContextPropagator")
    ThreadContext labelContextPropagator2;

    @Inject
    @NamedInstance("priority3Executor")
    Executor priority3Executor;

    @Produces
    @ApplicationScoped
    @NamedInstance("priority3Executor")
    public Executor createPriority3Executor(@NamedInstance("priorityContext") @ThreadContextConfig(propagated = THREAD_PRIORITY) ThreadContext ctx) {
        int originalPriority = Thread.currentThread().getPriority();
        try {
            Thread.currentThread().setPriority(3);
            Label.set("do-not-propagate-this-label");
            Buffer.set(new StringBuffer("do-not-propagate-this-buffer"));

            return ctx.currentContextExecutor();
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    @Inject
    @NamedInstance("priorityContext")
    ThreadContext threadPriorityContext;

    /**
     * Verify that injected ME instances injected by the container can be shutdown
     */
    public void shutdownContainerInstance() throws Exception {
        throwAway1.shutdown();
        assertTrue(throwAway1.awaitTermination(MAX_WAIT_SEC, TimeUnit.SECONDS));
        assertTrue(throwAway1.isShutdown());

        throwAway2.shutdownNow();
        assertTrue(throwAway2.awaitTermination(MAX_WAIT_SEC, TimeUnit.SECONDS));
        assertTrue(throwAway1.isShutdown());
    }

    /**
     * Extra sanity check test to verify injection is occurring. However, if CDI is 
     * set up properly, this bean should not even be reachable if injection fails. 
     */
    public void testVerifyInjection() {
        assertNotNull(exec);
        assertNotNull(maxQueued3);
        assertNotNull(appProduced);
        assertNotNull(appProduced_injected);
    }

    /**
     * Verify that injected ME instances are useable in a very basic way
     */
    public void testBasicExecutorUsable() throws Exception {
        assertEquals(exec.supplyAsync(() -> "hello").get(MAX_WAIT_SEC, TimeUnit.SECONDS), "hello");
        assertEquals(maxQueued3.supplyAsync(() -> "hello").get(MAX_WAIT_SEC, TimeUnit.SECONDS), "hello");
        assertEquals(appProduced.supplyAsync(() -> "hello").get(MAX_WAIT_SEC, TimeUnit.SECONDS), "hello");
        assertEquals(appProduced_injected.supplyAsync(() -> "hello").get(MAX_WAIT_SEC, TimeUnit.SECONDS), "hello");
    }

    /**
     * Verify that the @ManagedExecutorConfig annotation is applied to injection points
     */
    public void testConfigAnno() throws Exception {
        checkMaxQueued3(maxQueued3);
    }
    
    /**
     * Verify that the @ManagedExecutorConfig annotation is applied on parameters of CDI methods
     */
    public void testConfigAnnoOnParameter() throws Exception {
        checkMaxQueued3(appProduced_injected);
    }
    
    /**
     * Verify that 3 tasks/actions, and no more, can be queued when maxQueued is configured to 3.
     */
    public void checkMaxQueued3(ManagedExecutor executor) throws ExecutionException, InterruptedException, TimeoutException {
        Phaser barrier = new Phaser(1);
        try {
            // First, use up the single maxAsync slot with a blocking task and wait for it to start
            executor.submit(() -> barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_SEC, TimeUnit.SECONDS);

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

            Assert.assertEquals(future1.get(MAX_WAIT_SEC, TimeUnit.SECONDS), Integer.valueOf(101),
                    "Unexpected result of first task.");

            Assert.assertNull(future2.get(MAX_WAIT_SEC, TimeUnit.SECONDS),
                    "Unexpected result of second task.");

            // At least 2 queue positions must be available at this point
            Future<Integer> future6 = executor.submit(() -> 106);
            CompletableFuture<Integer> future7 = executor.supplyAsync(() -> 107);

            Assert.assertEquals(future3.get(MAX_WAIT_SEC, TimeUnit.SECONDS), Integer.valueOf(103),
                    "Unexpected result of third task.");

            Assert.assertEquals(future6.get(MAX_WAIT_SEC, TimeUnit.SECONDS), Integer.valueOf(106),
                    "Unexpected result of sixth task.");

            Assert.assertEquals(future7.get(MAX_WAIT_SEC, TimeUnit.SECONDS), Integer.valueOf(107),
                    "Unexpected result of seventh task.");
        }
        finally {
            barrier.forceTermination();
        }
    }

    /**
     * The container creates a ThreadContext instance per unqualified ThreadContext injection point.
     * This test relies on the spec requirement for ThreadContext.toString to include the
     * name of the injection point from which the container produces the instance.
     */
    public void testInstancePerUnqualifiedThreadContextInjectionPoint() {
        Assert.assertNotEquals(defaultContext.toString(), defaultContextWithEmptyAnno.toString(),
                "ThreadContext injection points without qualifiers did not receive unique instances.");

        Assert.assertNotEquals(defaultContext.toString(), bufferContext.toString(),
                "ThreadContext injection points without qualifiers did not receive unique instances.");

        Assert.assertNotEquals(defaultContext.toString(), labelContext.toString(),
                "ThreadContext injection points without qualifiers did not receive unique instances.");

        Assert.assertNotEquals(defaultContextWithEmptyAnno.toString(), bufferContext.toString(),
                "ThreadContext injection points without qualifiers did not receive unique instances.");

        Assert.assertNotEquals(defaultContextWithEmptyAnno.toString(), labelContext.toString(),
                "ThreadContext injection points without qualifiers did not receive unique instances.");

        Assert.assertNotEquals(bufferContext.toString(), labelContext.toString(),
                "ThreadContext injection points without qualifiers did not receive unique instances.");
    }

    /**
     * A ThreadContext instance that is injected by the container can be configured via ThreadContextConfig.
     * Test the values configured for bufferContext and labelContext.
     */
    public void testThreadContextConfig() {
        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testThreadContextConfig-label");
            Buffer.set(new StringBuffer("testThreadContextConfig-buffer"));

            Consumer<Integer> testBufferContext = bufferContext.contextualConsumer(expectedPriority -> {
                Assert.assertEquals(Buffer.get().toString(), "testThreadContextConfig-buffer",
                        "Thread context type was not propagated.");
                Assert.assertEquals(Label.get(), "",
                        "Thread context type was not cleared.");
                Assert.assertEquals(Integer.valueOf(Thread.currentThread().getPriority()), expectedPriority,
                        "Thread context type was not left unchanged.");
            });

            Runnable testLabelContext = labelContext.contextualRunnable(() -> {
                Assert.assertEquals(Label.get(), "testThreadContextConfig-label",
                        "Thread context type was not propagated.");
                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Thread context type was not cleared.");
                Assert.assertEquals(Buffer.get().toString(), "testThreadContextConfig-new-buffer",
                        "Thread context type was not left unchanged.");
            });

            Thread.currentThread().setPriority(4);
            Label.set("testThreadContextConfig-new-label");
            Buffer.set(new StringBuffer("testThreadContextConfig-new-buffer"));

            testBufferContext.accept(4);
            testLabelContext.run();
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * If the NamedInstance qualifier is present on a ThreadContext injection point that is annotated with ThreadContextConfig,
     * the container creates a ThreadContext instance that is qualified by the name value.
     */
    public void testThreadContextConfigNamedInstance() {
        Assert.assertNotNull(threadPriorityContext);

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testThreadContextConfigNamedInstance-label");
            Buffer.set(new StringBuffer("testThreadContextConfigNamedInstance-buffer"));

            Supplier<Boolean> testPriorityContext = threadPriorityContext.contextualSupplier(() -> {
                Assert.assertEquals(Thread.currentThread().getPriority(), newPriority,
                        "Thread context type was not propagated.");
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Thread context type (buffer) was not cleared.");
                Assert.assertEquals(Label.get(), "",
                        "Thread context type (label) was not cleared.");
                return true;
            });

            Thread.currentThread().setPriority(4);

            testPriorityContext.get();
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Application can provide producers of ThreadContext that are qualified by NamedInstance.
     */
    public void testAppDefinedProducerOfThreadContext() {
        Assert.assertNotNull(labelContextPropagator1,
                "Application should be able to use NamedInstance/Produces to define its own producer of ThreadContext.");
        Assert.assertNotNull(labelContextPropagator2,
                "Application should be able to use NamedInstance qualifier to obtain produced instance of ThreadContext.");

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testAppDefinedProducerOfThreadContext-label");
            Buffer.set(new StringBuffer("testAppDefinedProducerOfThreadContext-buffer"));

            Runnable testLabelContext = labelContextPropagator2.contextualRunnable(() -> {
                Assert.assertEquals(Label.get(), "testAppDefinedProducerOfThreadContext-label",
                        "Thread context type was not propagated.");
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Thread context type (Buffer) was not cleared.");
                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Thread context type (ThreadPriority) was not cleared.");
            });

            Label.set("testAppDefinedProducerOfThreadContext-new-label");

            testLabelContext.run();
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Application-defined producer methods can have injection points of ThreadContext that are provided by the container.
     */
    public void testAppDefinedProducerUsingInjectedThreadContext() {
        Assert.assertNotNull(priority3Executor,
                "Application should be able to create its own CDI producer that injects a ThreadContext that is provided by the container.");

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testAppDefinedProducerUsingInjectedThreadContext-label");
            Buffer.set(new StringBuffer("testAppDefinedProducerUsingInjectedThreadContext-buffer"));

            // priority3Executor is an application-produced instance, where its producer method injects a ThreadContext
            // instance that is provided by the container. The following verifies thread context propagation of that instance,
            priority3Executor.execute(() -> {
                Assert.assertEquals(Thread.currentThread().getPriority(), 3,
                        "Thread context type was not propagated.");
                Assert.assertEquals(Label.get(), "",
                        "Thread context type (Label) was not cleared.");
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Thread context type (Buffer) was not cleared.");
            });
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * When the container creates a ThreadContext for an injection point that lacks a ThreadContextConfig annotation,
     * its configuration is equivalent to ThreadContext.builder().build(), which is that all available thread context
     * types are propagated.
     */
    public void testInjectThreadContextNoConfig() {
        Assert.assertNotNull(defaultContext,
                "Container must produce ThreadContext instance for unqualified injection point that lacks ThreadContextConfig.");

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testInjectThreadContextNoConfig-label");
            Buffer.set(new StringBuffer("testInjectThreadContextNoConfig-buffer"));

            Function<Integer, String> testAllContextPropagated = defaultContext.contextualFunction(i -> {
                Assert.assertEquals(Buffer.get().toString(), "testInjectThreadContextNoConfig-buffer",
                        "Thread context type (Buffer) was not propagated.");
                Assert.assertEquals(Label.get(), "testInjectThreadContextNoConfig-label",
                        "Thread context type (Lable) was not propagated.");
                Assert.assertEquals(Thread.currentThread().getPriority(), newPriority,
                        "Thread context type (ThreadPriority) was not propagated.");
                return "done";
            });

            Thread.currentThread().setPriority(4);
            Label.set("testInjectThreadContextNoConfig-new-label");
            Buffer.set(new StringBuffer("testInjectThreadContextNoConfig-new-buffer"));

            testAllContextPropagated.apply(100);
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * When the container creates a ThreadContext for an injection point with an empty ThreadContextConfig annotation,
     * its configuration is equivalent to ThreadContext.builder().build(), which is that all available thread context
     * types are propagated.
     */
    public void testInjectThreadContextEmptyConfig() throws Exception {
        Assert.assertNotNull(defaultContextWithEmptyAnno,
                "Container must produce ThreadContext instance for injection point that is annotated with ThreadContextConfig.");

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testInjectThreadContextEmptyConfig-label");
            Buffer.set(new StringBuffer("testInjectThreadContextEmptyConfig-buffer"));

            Callable<Boolean> testAllContextPropagated = defaultContextWithEmptyAnno.contextualCallable(() -> {
                Assert.assertEquals(Buffer.get().toString(), "testInjectThreadContextEmptyConfig-buffer",
                        "Thread context type (Buffer) was not propagated.");
                Assert.assertEquals(Label.get(), "testInjectThreadContextEmptyConfig-label",
                        "Thread context type (Label) was not propagated.");
                Assert.assertEquals(Thread.currentThread().getPriority(), newPriority,
                        "Thread context type (ThreadPriority) was not propagated.");
                return true;
            });

            Thread.currentThread().setPriority(4);
            Label.set("testInjectThreadContextEmptyConfig-new-label");
            Buffer.set(new StringBuffer("testInjectThreadContextEmptyConfig-new-buffer"));

            testAllContextPropagated.call();
        }
        finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }
}
