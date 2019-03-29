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
package org.eclipse.microprofile.context.tck;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.microprofile.context.tck.MPConfigBean.Max5Queue;
import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.context.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.context.tck.contexts.label.Label;
import org.eclipse.microprofile.context.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.context.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
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

    @Inject @Max5Queue
    protected ManagedExecutor producedExecutor;

    @Inject @Named("producedThreadContext")
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
                .addPackages(true, "org.eclipse.microprofile.context.tck.contexts.buffer")
                .addPackages(true, "org.eclipse.microprofile.context.tck.contexts.label")
                .addPackage("org.eclipse.microprofile.context.tck.contexts.priority.spi")
                .addAsServiceProvider(ThreadContextProvider.class,
                        BufferContextProvider.class, LabelContextProvider.class, ThreadPriorityContextProvider.class);

        return ShrinkWrap.create(WebArchive.class, MPConfigTest.class.getSimpleName() + ".war")
                .addClass(MPConfigBean.class)
                .addClass(MPConfigTest.class)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsWebInfResource(new StringAsset(
                                "ManagedExecutor/maxAsync=1\n" +
                                        "ManagedExecutor/maxQueued=4\n" +
                                        "ManagedExecutor/propagated=Label,ThreadPriority\n" +
                                        "ManagedExecutor/cleared=Remaining\n" +
                                        "ThreadContext/cleared=Buffer\n" +
                                        "ThreadContext/propagated=\n" +
                                        "ThreadContext/unchanged=Remaining"),
                        "classes/META-INF/microprofile-config.properties")
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
     * Verify that the cleared and propagated attributes of a ManagedExecutor are defaulted
     * according to the defaults specified by the application in MicroProfile Config.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test
    public void defaultContextPropagationForManagedExecutorViaMPConfig()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1, maxQueued=4; propagated=Label,ThreadPriority; cleared=Remaining
        ManagedExecutor executor = ManagedExecutor.builder().build();

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 4 ? 3 : 4;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("defaultContextPropagationForManagedExecutorViaMPConfig-test-buffer"));
            Label.set("defaultContextPropagationForManagedExecutorViaMPConfig-test-label");

            // Run on separate thread to test propagated
            CompletableFuture<Void> stage1 = executor.completedFuture(newPriority)
                                                     .thenAcceptAsync(expectedPriority -> {
                Assert.assertEquals(Label.get(), "defaultContextPropagationForManagedExecutorViaMPConfig-test-label",
                        "Context type (Label) that MicroProfile config defaults to be propagated was not correctly propagated.");
                Assert.assertEquals(Integer.valueOf(Thread.currentThread().getPriority()), expectedPriority,
                        "Context type (ThreadPriority) that MicroProfile config defaults to be propagated was not correctly propagated.");
            });

            Assert.assertNull(stage1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
                    "Non-null value returned by stage that runs async Consumer.");

            // Run on current thread to test cleared
            CompletableFuture<Void> stage2 = stage1.thenRun(() ->
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that MicroProfile config overrides to be cleared was not cleared.")
            );

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
     * Verify that the cleared and propagated attributes of a ThreadContext are defaulted
     * according to the defaults specified by the application in MicroProfile Config.
     */
    @Test
    public void defaultContextPropagationForThreadContextViaMPConfig() {
        // Expected config is propagated={}; cleared=Buffer; unchanged=Remaining
        ThreadContext threadContext = ThreadContext.builder().build();

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default value
            Buffer.set(new StringBuffer("defaultContextPropagationForThreadContextViaMPConfig-test-buffer-A"));
            int newPriority = originalPriority == 3 ? 2 : 3;

            Runnable task = threadContext.contextualRunnable(() -> {
                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type that MicroProfile config defaults to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getPriority(), newPriority,
                        "Context type (ThreadPriority) that MicroProfile Config defaults to remain unchanged was changed.");

                Assert.assertEquals(Label.get(), "defaultContextPropagationForThreadContextViaMPConfig-test-label-B",
                        "Context type (Label) that MicroProfile Config defaults to remain unchanged was changed.");

                Label.set("defaultContextPropagationForThreadContextViaMPConfig-test-label-A");
            });

            // Set non-default values
            Thread.currentThread().setPriority(newPriority);
            Label.set("defaultContextPropagationForThreadContextViaMPConfig-test-label-B");

            task.run();

            Assert.assertEquals(Label.get(), "defaultContextPropagationForThreadContextViaMPConfig-test-label-A",
                    "Context type that MicroProfile Config defaults to remain unchanged was changed when task completed.");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    /**
     * Verify that the maxAsync and maxQueued attributes of a ManagedExecutor are defaulted
     * according to the defaults specified by the application in MicroProfile Config.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test(dependsOnMethods = "beanInjected")
    public void defaultMaxAsyncAndMaxQueuedForManagedExecutorViaMPConfig()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1, maxQueued=4; propagated=Buffer; cleared=Remaining
        ManagedExecutor executor = ManagedExecutor.builder().propagated(Buffer.CONTEXT_NAME).build();

        Phaser barrier = new Phaser(1);
        try {
            // Set non-default values
            Buffer.set(new StringBuffer("defaultMaxAsyncAndMaxQueuedForManagedExecutorViaMPConfig-test-buffer"));
            Label.set("defaultMaxAsyncAndMaxQueuedForManagedExecutorViaMPConfig-test-label");

            // First, use up the single maxAsync slot with a blocking task and wait for it to start
            executor.submit(() -> barrier.awaitAdvanceInterruptibly(barrier.arrive() + 1));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // Use up queue positions
            CompletableFuture<String> cf1 = executor.supplyAsync(() -> Buffer.get().toString());
            CompletableFuture<String> cf2 = executor.supplyAsync(() -> Label.get());
            CompletableFuture<String> cf3 = executor.supplyAsync(() -> "III");
            CompletableFuture<String> cf4 = executor.supplyAsync(() -> "IV");

            // Fifth attempt to queue a task must be rejected
            try {
                CompletableFuture<String> cf5 = executor.supplyAsync(() -> "V");
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

            Assert.assertEquals(cf1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "defaultMaxAsyncAndMaxQueuedForManagedExecutorViaMPConfig-test-buffer",
                    "First task: Context not propagated as configured on the builder.");

            Assert.assertEquals(cf2.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "",
                    "Second task: Context not cleared as defaulted by MicroProfile Config property.");

            Assert.assertEquals(cf3.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "III",
                    "Unexpected result of third task.");

            Assert.assertEquals(cf4.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS), "IV",
                    "Unexpected result of fourth task.");
        }
        finally {
            barrier.forceTermination();

            // Restore original values
            Buffer.set(null);
            Label.set(null);
        }
    }

    /**
     * Verify that MicroProfile config defaults the cleared attribute when only the
     * propagated and maxQueued attributes are specified by the application.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test(dependsOnMethods = "beanInjected")
    public void explicitlySpecifiedPropagatedTakesPrecedenceOverDefaults()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1; maxQueued=5; propagated={}; cleared=Remaining
        CompletableFuture<Integer> completedFuture = bean.getCompletedFuture();
        Assert.assertNotNull(completedFuture);

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            int newPriority = originalPriority == 2 ? 1 : 2;
            Thread.currentThread().setPriority(newPriority);
            Buffer.set(new StringBuffer("explicitlySpecifiedPropagatedTakesPrecedenceOverDefaults-test-buffer"));
            Label.set("explicitlySpecifiedPropagatedTakesPrecedenceOverDefaults-test-label");

            // Run on current thread to test that none are propagated and all are cleared
            CompletableFuture<Void> stage1 = completedFuture.thenRun(() -> {
                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type (ThreadPriority) that is explicitly configured to be cleared was not cleared.");

                Assert.assertEquals(Buffer.get().toString(), "",
                        "Context type (Buffer) that is explicitly configured to be cleared was not cleared.");

                Assert.assertEquals(Label.get(), "",
                        "Context type (Label) that is explicitly configured to be cleared was not cleared.");
            });

            Assert.assertNull(stage1.get(MAX_WAIT_NS, TimeUnit.NANOSECONDS),
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
     * Verify that MicroProfile config does not default any attributes when all attributes
     * are explicitly specified by the application.
     */
    @Test(dependsOnMethods = "beanInjected")
    public void explicitlySpecifyAllAttributesOfThreadContext() {

        // Expected config is propagated=Buffer; unchanged={}, cleared=Remaining
        Executor contextSnapshot = bean.getContextSnapshot();
        Assert.assertNotNull(contextSnapshot);

        int originalPriority = Thread.currentThread().getPriority();
        try {
            // Set non-default values
            Buffer.set(new StringBuffer("explicitlySpecifyAllAttributesOfThreadContext-test-buffer"));
            Label.set("explicitlySpecifyAllAttributesOfThreadContext-test-label");

            int newPriority = originalPriority == 2 ? 1 : 2;
            Thread.currentThread().setPriority(newPriority);

            contextSnapshot.execute(() -> {
                Assert.assertEquals(Buffer.get().toString(), "setContextSnapshot-test-buffer",
                        "Context type that is explicitly configured to propagated was not propagated.");

                Assert.assertEquals(Label.get(), "",
                        "Context type (Label) that is explicitly configured to be cleared was not cleared.");

                Assert.assertEquals(Thread.currentThread().getPriority(), Thread.NORM_PRIORITY,
                        "Context type (ThreadPriority) that is explicitly configured to be cleared was not cleared.");
            });
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }
    
    /**
     * Verify that MicroProfile config defaults the maxAsync attribute and honors the explicitly specified
     * maxQueued attribute, when only the propagated and maxQueued attributes are specified by the application.
     *
     * @throws ExecutionException indicates test failure
     * @throws InterruptedException indicates test failure
     * @throws TimeoutException indicates test failure
     */
    @Test(dependsOnMethods = "beanInjected")
    public void explicitlySpecifyMaxQueued5()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Expected config is maxAsync=1; maxQueued=5; propagated={}; cleared=Remaining
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
}
