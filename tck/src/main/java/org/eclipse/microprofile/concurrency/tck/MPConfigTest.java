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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrency.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
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

    @Inject @NamedInstance("producedExecutor")
    protected ManagedExecutor producedExecutor;

    @AfterMethod
    public void afterMethod(Method m) {
        System.out.println("<<< END MPConfigTest." + m.getName());
    }

    @BeforeMethod
    public void beforeMethod(Method m) {
        System.out.println(">>> BEGIN MPConfigTest." + m.getName());
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
                .addAsLibraries(fakeContextProviders);
    }

    /**
     * Determine if instances injected properly, which is a prerequisite of running this tests.
     */
    @Test
    public void beansInjected() {
        Assert.assertNotNull(bean,
                "Unable to inject CDI bean. Expect other tests to fail.");

        Assert.assertNotNull(bean.getExecutorWithConfig(),
                "Unable to inject ManagedExecutor into CDI bean. Expect other tests to fail.");

        Assert.assertNotNull(namedExecutor,
                "Unable to inject ManagedExecutor qualified by NamedInstance. Expect other tests to fail.");

        Assert.assertNotNull(producedExecutor,
                "Unable to inject ManagedExecutor qualified by NamedInstance. Expect other tests to fail.");

        Assert.assertNotNull(bean.getCompletedFuture(),
                "Unable to inject CompletableFuture (which injects ManagedExecutor) into CDI bean. Expect other tests to fail.");        
    }

    /**
     * Verify that MicroProfile config overrides the cleared and propagated attributes
     * of a ManagedExecutor that is produced by the container because the application annotated
     * an injection point with ManagedExecutorConfig and the NamedInstance qualifier.
     */
    @Test
    public void overrideManagedExecutorFieldWithConfigAndNameToChangePropagation()
            throws ExecutionException, InterruptedException, TimeoutException {
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
     * Verify that 4 tasks/actions, and no more, can be queued when MicroProfile Config
     * overrides the maxQueued value with 4 on a ManagedExecutor that is produced by the
     * container because the application annotated an injection point with ManagedExecutorConfig
     * and the NamedInstance qualifier.
     */
    @Test
    public void overrideManagedExecutorFieldWithConfigAndNameToHaveMaxQueued4()
            throws ExecutionException, InterruptedException, TimeoutException {
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
                Assert.fail("Exceeded maxQueued of 4. Future for 5th queued task/action is " + cf5);
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
}
