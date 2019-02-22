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
package org.eclipse.microprofile.concurrency.tck;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.literal.NamedLiteral;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrency.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.ThreadContextConfig;
import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.Test;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

public class MPConfigTest extends Arquillian {
    /**
     * Maximum tolerated wait for an asynchronous operation to complete.
     * This is important to ensure that tests don't hang waiting for asynchronous operations to complete.
     * Normally these sort of operations will complete in tiny fractions of a second, but we are specifying
     * an extremely generous value here to allow for the widest possible variety of test execution environments.
     */
    private static final long MAX_WAIT_NS = TimeUnit.MINUTES.toNanos(2);

    @Inject
    protected MPConfigBean bean;

    @Inject @NamedInstance("namedExecutor")
    protected ManagedExecutor namedExecutor;

    @Inject @NamedInstance("namedThreadContext")
    protected ThreadContext namedThreadContext;
    
    @Inject @NamedInstance("clearAllRemainingThreadContext")
    protected ThreadContext clearAllRemainingThreadContext;

    @Inject @NamedInstance("producedExecutor")
    protected ManagedExecutor producedExecutor;

    @Inject @Named("producedThreadContext") // other qualifiers such as @Named remain valid for app-defined producers
    protected ThreadContext producedThreadContext;

    @AfterMethod
    public void afterMethod(Method m, ITestResult result) {
        System.out.println("<<< END " + m.getClass().getSimpleName() + '.' + m.getName() + (result.isSuccess() ? " SUCCESS" : " FAILED"));
        Throwable failure = result.getThrowable();
        if (failure != null) {
            failure.printStackTrace(System.out);
        }
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

        return ShrinkWrap.create(WebArchive.class, MPConfigTest.class.getSimpleName() + ".war")
                .addClass(MPConfigBean.class)
                .addClass(MPConfigTest.class)
                .addAsManifestResource("META-INF/microprofile-config.properties", "microprofile-config.properties")
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsLibraries(fakeContextProviders);
    }

    /**
     * Determine if instances injected properly, which is a prerequisite of running these tests.
     */
    @Test
    public void beanInjected() {
        Assert.assertNotNull(bean,
                "Unable to inject CDI bean. Expect other tests to fail.");
    }

    /**
     * Verify that MicroProfile config overrides the cleared and propagated attributes
     * of a ManagedExecutor that is produced by the container because the application annotated
     * an unqualified injection point with ManagedExecutorConfig.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForManagedExecutorFieldWithConfig()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=2; maxQueued=3; propagated=Label; cleared=Remaining
        ManagedExecutor executor = bean.getExecutorWithConfig();
        Assert.assertNotNull(executor,
                "Unable to inject ManagedExecutor with ManagedExecutorConfig. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 3 ? 2 : 3;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("overrideManagedExecutorConfig-test-buffer"));
            Label.set("overrideManagedExecutorConfig-test-label");

            // Run on separate thread to test propagated
            CompletableFuture<Void> stage1 = namedExecutor.runAsync(() ->
                Assert.assertEquals(Label.get(), "overrideManagedExecutorConfig-test-label",
                        "Context type that MicroProfile config overrides to be propagated was not correctly propagated.")
            );

            Assert.assertNull(stage1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable async.");

            // Run on current thread to test cleared
            CompletableFuture<Void> stage2 = stage1.thenRun(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that MicroProfile config overrides to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type (ThreadPriority) that MicroProfile config overrides to be cleared was not cleared.");
            });

            Assert.assertNull(stage2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that MicroProfile config overrides the cleared and propagated attributes
     * of a ManagedExecutor that is produced by the container because the application annotated
     * an injection point with ManagedExecutorConfig and the NamedInstance qualifier.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForManagedExecutorFieldWithConfigAndName()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1, maxQueued=4; cleared=ThreadPriority,Buffer,Transaction; propagated=Remaining
        Assert.assertNotNull(namedExecutor,
                "Unable to inject ManagedExecutor qualified by NamedInstance. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 4 ? 3 : 4;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("configOverrideCP-test-buffer"));
            Label.set("configOverrideCP-test-label");

            // Run on separate thread to test propagated
            CompletableFuture<Void> stage1 = namedExecutor.runAsync(() ->
                Assert.assertEquals(Label.get(), "configOverrideCP-test-label",
                        "Context type that MicroProfile config overrides to be propagated was not correctly propagated.")
            );

            Assert.assertNull(stage1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable async.");

            // Run on current thread to test cleared
            CompletableFuture<Void> stage2 = stage1.thenRun(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that MicroProfile config overrides to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type (ThreadPriority) that MicroProfile config overrides to be cleared was not cleared.");
            });

            Assert.assertNull(stage2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that MicroProfile config overrides the cleared and propagated attributes
     * of a ManagedExecutor that is produced by the container when the application defines
     * a parameter injection point that is neither qualified nor annotated with ManagedExecutorConfig.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForManagedExecutorParameter()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1; maxQueued=2; propagated=Buffer,Label; cleared=Remaining
        CompletableFuture<Integer> completedFuture = bean.getCompletedFuture();
        Assert.assertNotNull(completedFuture,
                "Unable to inject ManagedExecutor into method parameter. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 2 ? 1 : 2;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("overrideManagedExecutorConfig-test-buffer"));
            Label.set("overrideManagedExecutorConfig-test-label");

            // Run on current thread to test cleared
            CompletableFuture<Void> stage1 = completedFuture.thenRun(() ->
                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type that MicroProfile config overrides to be cleared was not cleared.")
            );

            Assert.assertNull(stage1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable.");

            // Run on separate thread to test propagated
            CompletableFuture<Void> stage2 = completedFuture.thenRunAsync(() -> {
                Assert.assertEquals(Label.get(), "overrideManagedExecutorConfig-test-label",
                        "Context type (Label) that MicroProfile config overrides to be propagated was not correctly propagated.");

                Assert.assertEquals(Buffer.get().toString(), "overrideManagedExecutorConfig-test-buffer",
                        "Context type (Buffer) that MicroProfile config overrides to be propagated was not correctly propagated.");
            });

            Assert.assertNull(stage2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs Runnable async.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that MicroProfile config overrides the cleared, propagated, and unchanged attributes
     * of a ThreadContext that is produced by the container when the application defines
     * a field injection point that is neither qualified nor annotated with ThreadContextConfig.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForThreadContextField() {

        // Expected config is propagated={}; cleared=Buffer,ThreadPriority; unchanged=Remaining
        ThreadContext threadContext = bean.getThreadContext();
        Assert.assertNotNull(threadContext,
                "Unable to inject ThreadContext into field parameter. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 3 ? 2 : 3;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("overrideThreadContext-test-buffer-A"));
            Label.set("overrideThreadContext-test-label-A");

            Runnable task = threadContext.contextualRunnable(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that MicroProfile config overrides to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type (ThreadPriority) that MicroProfile config overrides to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "overrideThreadContext-test-label-A",
                        "Context type that MicroProfile config overrides to remain unchanged was changed.");

                Label.set("overrideThreadContext-test-label-B");
            });

            task.run();

            Assert.assertEquals(Label.get(), "overrideThreadContext-test-label-B",
                    "Context type that MicroProfile config overrides to remain unchanged was changed when task completed.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that MicroProfile config overrides the propagated attributes
     * of a ThreadContext that is produced by the container when the application defines
     * a field injection point that is annotated with ThreadContextConfig and the NamedInstance qualifier.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForThreadContextFieldWithConfigAndName() {

        // Expected config is propagated=Label,Buffer; cleared={}, unchanged=Remaining
        Assert.assertNotNull(namedThreadContext,
                "Unable to inject ThreadContext qualified by NamedInstance. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 3 ? 2 : 3;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("overrideNamedThreadContextConfig-test-buffer-A"));
            Label.set("overrideNamedThreadContextConfig-test-label-A");

            Consumer<Integer> task = namedThreadContext.contextualConsumer(expectedPriority -> {
                Assert.assertEquals(Buffer.get().toString(), "overrideNamedThreadContextConfig-test-buffer-A",
                        "Context type (Buffer) that MicroProfile config specifies to be propagated was not propagated.");

                Assert.assertEquals(Label.get(), "overrideNamedThreadContextConfig-test-label-A",
                        "Context type (Label) that MicroProfile config overrides to be propagated was not propagated.");

                Assert.assertEquals(Integer.valueOf(Thread.currentThread().getPriority()), expectedPriority,
                        "Context type that MicroProfile config overrides to remain unchanged was changed.");

                Thread.currentThread().setPriority(expectedPriority - 1);
            });

            Buffer.set(new StringBuffer("overrideNamedThreadContextConfig-test-buffer-B"));
            Label.set("overrideNamedThreadContextConfig-test-label-B");

            task.accept(newPriority);

            Assert.assertEquals(Thread.currentThread().getPriority(), newPriority - 1,
                    "Context type that MicroProfile config overrides to remain unchanged was changed when task completed.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that MicroProfile config overrides the cleared, propagated, and unchanged attributes
     * of a ThreadContext that is produced by the container when the application defines
     * an unqualified parameter injection point that is annotated with ThreadContextConfig,
     * where the ThreadContext parameter is the first parameter of the method.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForThreadContextParameter1WithConfig() {

        // Expected config is propagated=Label; unchanged=ThreadPriority, cleared=Remaining
        Executor contextSnapshot = bean.getContextSnapshot();
        Assert.assertNotNull(contextSnapshot,
                "Unable to inject ThreadContext into method parameter 1. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            Buffer.set(new StringBuffer("overrideThreadContextConfig-test-buffer"));
            Label.set("overrideThreadContextConfig-test-label");

            int newPriority = originalPriority == 2 ? 1 : 2;

            contextSnapshot.execute(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that MicroProfile config overrides to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "setContextSnapshot-test-label",
                        "Context type that MicroProfile config overrides to be propagated was not correctly propagated from original snapshot.");

                Assert.assertEquals(Thread.currentThread().getPriority(), originalPriority,
                        "Context type that MicroProfile config overrides to remain unchanged was changed.");

                Thread.currentThread().setPriority(newPriority);
            });

            Assert.assertEquals(Thread.currentThread().getPriority(), newPriority,
                    "Context type that MicroProfile config overrides to remain unchanged was changed when task completed.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }
    
    /**
     * Verify MicroProfile Config overrides the propagated attribute to enabled
     * an implied clear of all remaining.
     * @throws Exception 
     */
    @Test
    public void overrideContextPropagationForThreadContextWithImpliedCleared() throws Exception {
        // Expected config is propagated=Buffer; cleared={} (implied Remaining); unchanged=Label
        Assert.assertNotNull(clearAllRemainingThreadContext,
                "Unable to inject ThreadContext qualified by NamedInstance. Cannot run test.");
        
        int originalPriority = Thread.currentThread().getPriority();     
        try {
            // Set non-default values
            int newPriority = originalPriority == 3 ? 2 : 3;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("clearUnspecifiedContexts-test-buffer-A"));
            Label.set("clearUnspecifiedContexts-test-label-A");

            Callable<Integer> callable = clearAllRemainingThreadContext.contextualCallable(() -> {
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
     * Verify that MicroProfile config overrides the cleared, propagated, and unchanged attributes
     * of a ThreadContext that is produced by the container when the application defines
     * an unqualified parameter injection point that is annotated with ThreadContextConfig,
     * where the ThreadContext parameter is the third parameter of the method.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideContextPropagationForThreadContextParameter3WithConfig() {

        // Expected config is propagated=Label; unchanged=ThreadPriority, cleared=Remaining
        Assert.assertNotNull(producedThreadContext,
                "Unable to inject ThreadContext. Cannot run test.");

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            Buffer.set(new StringBuffer("overrideThreadContextConfig3-test-buffer"));
            Label.set("overrideThreadContextConfig3-test-label-A");

            Supplier<String> appendLabelToBuffer = producedThreadContext.contextualSupplier(() ->
                Buffer.get().append(Label.get()).toString());

            Label.set("overrideThreadContextConfig3-test-label-B");

            Assert.assertEquals(appendLabelToBuffer.get(), "overrideThreadContextConfig3-test-label-A",
                    "Context type(s) not correctly propagated and/or cleared per MicroProfile Config overrides.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that 5 tasks/actions, and no more, can be queued when MicroProfile Config
     * overrides the maxAsync value with 1 and leaves the maxAsync of 5 unmodified on a
     * ManagedExecutor that is produced by the container when the application defines a
     * parameter injection point that is annotated with ManagedExecutorConfig but not
     * with any qualifiers.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideMaxAsyncWith1ForManagedExecutorParameter()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1; maxQueued=5; cleared=Transaction; propagated=Remaining
        Assert.assertNotNull(producedExecutor, "Injection failure. Cannot run test.");

        Phaser barrier = new Phaser(1);
        try {
            // First, use up the single maxAsync slot with a blocking task and wait for it to start
            producedExecutor.submit(() -> barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Use up queue positions
            CompletableFuture<String> future1 = producedExecutor.supplyAsync(() -> "Q_1");
            CompletableFuture<String> future2 = producedExecutor.supplyAsync(() -> "Q_2");
            CompletableFuture<String> future3 = producedExecutor.supplyAsync(() -> "Q_3");
            Future<String> future4 = producedExecutor.submit(() -> "Q_4");
            Future<String> future5 = producedExecutor.submit(() -> "Q_5");

            // Sixth attempt to queue a task must be rejected
            try {
                Future<String> future6 = producedExecutor.submit(() -> "Q_6");
                Assert.fail("Exceeded maxQueued of 5. Future for 6th queued task/action is " + future6);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            // unblock and allow tasks to finish
            barrier.arrive();

            Assert.assertEquals(future1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q_1",
                    "Unexpected result of first task.");

            Assert.assertEquals(future2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q_2",
                    "Unexpected result of second task.");

            Assert.assertEquals(future3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q_3",
                    "Unexpected result of third task.");

            Assert.assertEquals(future4.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q_4",
                    "Unexpected result of fourth task.");

            Assert.assertEquals(future5.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q_5",
                    "Unexpected result of fifth task.");
        }
        finally {
            barrier.forceTermination();
        } 
    }

    /**
     * Verify that 2 tasks/actions, and no more, can be queued when MicroProfile Config
     * overrides the maxQueued value with 2 on a ManagedExecutor that is produced by the
     * container when the application defines a parameter injection point that is neither
     * qualified nor annotated with ManagedExecutorConfig.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideMaxQueuedWith2ForManagedExecutorParameter()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1; maxQueued=2; propagated=Buffer,Label; cleared=Remaining
        CompletableFuture<Integer> completedFuture = bean.getCompletedFuture();
        Assert.assertNotNull(completedFuture, "Injection failure. Cannot run test.");

        Phaser barrier = new Phaser(1);
        try {
            // First, use up the single maxAsync slot with a blocking task and wait for it to start
            completedFuture.thenRunAsync(() -> {
                try {
                    barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1);
                }
                catch (InterruptedException x) {
                    throw new CompletionException(x);
                }
            });
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Use up queue positions
            CompletableFuture<String> cf1 = completedFuture.thenApplyAsync(unused -> "first");
            Assert.assertFalse(cf1.isDone(), "First action should be queued, not completed.");

            CompletableFuture<String> cf2 = completedFuture.thenApplyAsync(unused -> "second");
            Assert.assertFalse(cf2.isDone(), "Second action should be queued, not completed.");

            // Third attempt to queue a task must be rejected
            try {
                CompletableFuture<String> cf3 = completedFuture.thenApplyAsync(unused -> "third");
                // CompletionStage interface does not provide detail on precisely how to report rejection,
                // so tolerate both possibilities: exception raised or stage returned with exceptional completion.   
                Assert.assertTrue(cf3.isDone() && cf3.isCompletedExceptionally(),
                        "Exceeded maxQueued of 2. Future for 3rd queued task/action is " + cf3);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            // unblock and allow tasks to finish
            barrier.arrive();

            Assert.assertEquals(cf1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "first",
                    "Unexpected result of first task.");

            Assert.assertEquals(cf2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "second",
                    "Unexpected result of second task.");
        }
        finally {
            barrier.forceTermination();
        }
    }

    /**
     * Verify that 3 tasks/actions, and no more, can be queued when MicroProfile Config
     * overrides the maxQueued value with 3 on a ManagedExecutor that is produced by the
     * container because the application annotated an unqualified injection point with
     * the ManagedExecutorConfig annotation.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideMaxQueuedWith3ForManagedExecutorFieldWithConfig()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=2; maxQueued=3; propagated=Label; cleared=Remaining
        ManagedExecutor executor = bean.getExecutorWithConfig();
        Assert.assertNotNull(executor,
                "Unable to inject ManagedExecutor with ManagedExecutorConfig. Cannot run test.");

        Phaser barrier = new Phaser(2);
        try {
            // First, use up both maxAsync slots with blocking tasks and wait for those tasks to start
            CompletableFuture<Integer> blocker1 = executor.supplyAsync(() -> barrier.awaitAdvance(barrier.arriveAndAwaitAdvance()));
            CompletableFuture<Integer> blocker2 = executor.supplyAsync(() -> barrier.awaitAdvance(barrier.arriveAndAwaitAdvance()));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Use up queue positions
            Future<String> future1 = executor.supplyAsync(() -> "q1");
            Future<String> future2 = executor.supplyAsync(() -> "q2");
            Future<String> future3 = executor.submit(() -> "q3");

            // Fourth attempt to queue a task must be rejected
            try {
                Future<String> cf4 = executor.submit(() -> "q4");
                Assert.fail("Exceeded maxQueued of 3. Future for 4th queued task/action is " + cf4);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            // unblock and allow tasks to finish
            barrier.arrive();
            barrier.arrive(); // there are 2 parties in each phase

            Assert.assertEquals(blocker1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(2),
                    "Unexpected result of first blocker task.");

            Assert.assertEquals(blocker2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), Integer.valueOf(2),
                    "Unexpected result of second blocker task.");

            Assert.assertEquals(future1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "q1",
                    "Unexpected result of first task from queue.");

            Assert.assertEquals(future2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "q2",
                    "Unexpected result of second task from queue.");

            Assert.assertEquals(future3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "q3",
                    "Unexpected result of third task from queue.");
        }
        finally {
            barrier.forceTermination();
        }
    }

    /**
     * Verify that 4 tasks/actions, and no more, can be queued when MicroProfile Config
     * overrides the maxQueued value with 4 on a ManagedExecutor that is produced by the
     * container because the application annotated an injection point with ManagedExecutorConfig
     * and the NamedInstance qualifier.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void overrideMaxQueuedWith4ForManagedExecutorFieldWithConfigAndName()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1, maxQueued=4; cleared=ThreadPriority,Buffer,Transaction; propagated=Remaining
        Assert.assertNotNull(namedExecutor,
                "Unable to inject ManagedExecutor qualified by NamedInstance. Cannot run test.");

        Phaser barrier = new Phaser(1);
        try {
            // First, use up the single maxAsync slot with a blocking task and wait for it to start
            namedExecutor.submit(() -> barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Use up queue positions
            CompletableFuture<String> cf1 = namedExecutor.supplyAsync(() -> "Q1");
            CompletableFuture<String> cf2 = namedExecutor.supplyAsync(() -> "Q2");
            CompletableFuture<String> cf3 = namedExecutor.supplyAsync(() -> "Q3");
            CompletableFuture<String> cf4 = namedExecutor.supplyAsync(() -> "Q4");

            // Fifth attempt to queue a task must be rejected
            try {
                CompletableFuture<String> cf5 = namedExecutor.supplyAsync(() -> "Q5");
                // CompletableFuture interface does not provide detail on precisely how to report rejection,
                // so tolerate both possibilities: exception raised or stage returned with exceptional completion.   
                Assert.assertTrue(cf5.isDone() && cf5.isCompletedExceptionally(),
                        "Exceeded maxQueued of 4. Future for 5th queued task/action is " + cf5);
            }
            catch (RejectedExecutionException x) {
                // test passes
            }

            // unblock and allow tasks to finish
            barrier.arrive();

            Assert.assertEquals(cf1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q1",
                    "Unexpected result of first task.");

            Assert.assertEquals(cf2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q2",
                    "Unexpected result of second task.");

            Assert.assertEquals(cf3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q3",
                    "Unexpected result of third task.");

            Assert.assertEquals(cf4.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "Q4",
                    "Unexpected result of fourth task.");
        }
        finally {
            barrier.forceTermination();
        }
    }

    /**
     * CDI programmatic lookup of ManagedExecutor without any qualifiers must not find anything.
     * This test is possible for ManagedExecutor because the test application does not provide any
     * producers of ManagedExecutor without qualifiers.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void programmaticLookupManagedExecutorWithoutQualifiers() {
        CDI cdi = CDI.current();

        Instance<ManagedExecutor> instance = cdi.select(ManagedExecutor.class);
        Assert.assertTrue(instance.isUnsatisfied(),
                "Looked up instance of ManagedExecutor without qualifiers: " + instance);
    }

    /**
     * CDI programmatic lookup of ManagedExecutor must be possible based on NamedInstance qualifier,
     * but not by using ManagedExecutorConfig, because ManagedExecutorConfig is not a qualifier.
     * This test uses literals for ManagedExecutorConfig that match the values directly from the
     * annotation as well as those that are overridden by MicroProfile Config in order to test
     * that in neither circumstance do they allow an instance to be looked up programmatically.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void programmaticLookupManagedExecutorWithQualifier() {
        CDI cdi = CDI.current();

        NamedInstance.Literal namedInstance = NamedInstance.Literal.of("namedExecutor");
        Instance<ManagedExecutor> instance = cdi.select(ManagedExecutor.class, namedInstance);
        ManagedExecutor executor = instance.get();
        Assert.assertEquals(executor.toString(), namedExecutor.toString(),
                "Programmatic CDI lookup of ManagedExecutor with " + namedInstance.toString() + " qualifier returned wrong instance.");

        // Try matching annotation values before MP Config is applied,
        ManagedExecutorConfig.Literal managedExecutorConfig = ManagedExecutorConfig.Literal.of(
                1,
                -1,
                new String[] { ThreadContext.ALL_REMAINING }, // cleared
                new String[] { Buffer.CONTEXT_NAME, Label.CONTEXT_NAME }); // propagated 
        try {
            instance = cdi.select(ManagedExecutor.class, managedExecutorConfig);
            Assert.fail("Should not resolve an instance because " + managedExecutorConfig.toString() + " is not a qualifier.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        // Try matching annotation values after MP Config is applied,
        managedExecutorConfig = ManagedExecutorConfig.Literal.of(
                1,
                4,
                new String[] { ThreadPriorityContextProvider.THREAD_PRIORITY, Buffer.CONTEXT_NAME, ThreadContext.TRANSACTION }, // cleared
                new String[] { ThreadContext.ALL_REMAINING }); // propagated 
        try {
            instance = cdi.select(ManagedExecutor.class, namedInstance, managedExecutorConfig);
            Assert.fail("Should not resolve an instance because " + managedExecutorConfig.toString() + " is not a qualifier.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }
    }

    /**
     * CDI programmatic lookup of ThreadContext must be possible based on NamedInstance qualifier,
     * but not by using ThreadContextConfig, because ThreadContextConfig is not a qualifier.
     * This test uses literals for ThreadContextConfig that match the values directly from the
     * annotation as well as those that are overridden by MicroProfile Config in order to test
     * that in neither circumstance do they allow an instance to be looked up programmatically. 
     */
    @Test(dependsOnMethods = "beanInjected")
    public void programmaticLookupThreadContextWithQualifier() {
        CDI cdi = CDI.current();

        NamedInstance.Literal namedInstance = NamedInstance.Literal.of("namedThreadContext");
        Instance<ThreadContext> instance = cdi.select(ThreadContext.class, namedInstance);
        ThreadContext threadContext = instance.get();
        Assert.assertEquals(threadContext.toString(), namedThreadContext.toString(),
                "Programmatic CDI lookup of ThreadContext with " + namedInstance.toString() + " qualifier returned wrong instance.");

        namedInstance = NamedInstance.Literal.of("clearAllRemainingThreadContext");
        instance = cdi.select(ThreadContext.class, namedInstance);
        threadContext = instance.get();
        Assert.assertEquals(threadContext.toString(), clearAllRemainingThreadContext.toString(),
                "Programmatic CDI lookup of ThreadContext with " + namedInstance.toString() + " qualifier returned wrong instance.");

        // Verify that existing qualifiers such as Named aren't broken by the MP Concurrency implementation,
        NamedLiteral named = NamedLiteral.of("producedThreadContext");
        instance = cdi.select(ThreadContext.class, named);
        threadContext = instance.get();
        Assert.assertEquals(threadContext.toString(), producedThreadContext.toString(),
                "Programmatic CDI lookup of ThreadContext with " + named.toString() + " qualifier returned wrong instance.");

        // Try matching annotation values before MP Config is applied,
        ThreadContextConfig.Literal threadContextConfig = ThreadContextConfig.Literal.of(
                new String[] { }, // cleared
                new String[] { ThreadContext.ALL_REMAINING }, // propagated
                new String[] { Label.CONTEXT_NAME }); // unchanged
        try {
            instance = cdi.select(ThreadContext.class, threadContextConfig);
            Assert.fail("Should not resolve an instance because " + threadContextConfig.toString() + " is not a qualifier.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }

        // Try matching annotation values after MP Config is applied,
        threadContextConfig = ThreadContextConfig.Literal.of(
                new String[] { }, // cleared
                new String[] { Buffer.CONTEXT_NAME }, // propagated
                new String[] { Label.CONTEXT_NAME }); // unchanged
        try {
            instance = cdi.select(ThreadContext.class, namedInstance, threadContextConfig);
            Assert.fail("Should not resolve an instance because " + threadContextConfig.toString() + " is not a qualifier.");
        }
        catch (IllegalArgumentException x) {
            // test passes
        }
    }
}
