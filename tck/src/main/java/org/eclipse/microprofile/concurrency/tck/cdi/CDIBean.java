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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.testng.Assert;

@RequestScoped
public class CDIBean {

    @Inject
    ManagedExecutor exec;

    @Inject
    @NamedInstance("maxAsync2")
    @ManagedExecutorConfig(maxAsync = 2)
    ManagedExecutor maxAsync2;

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
    public ManagedExecutor createExecInjected(@ManagedExecutorConfig(maxAsync = 2) ManagedExecutor exec) {
        System.out.println("@AGG in producer with exec: " + exec);
        Thread.dumpStack();
        return exec;
    }

    /**
     * Verify that injected ME instances injected by the container can be shutdown
     */
    public void shutdownContainerInstance() throws Exception {
        throwAway1.shutdown();
        assertTrue(throwAway1.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(throwAway1.isShutdown());

        throwAway2.shutdownNow();
        assertTrue(throwAway2.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(throwAway1.isShutdown());
    }

    /**
     * Extra sanity check test to verify injection is occurring. However, if CDI is 
     * set up properly, this bean should not even be reachable if injection fails. 
     */
    public void testVerifyInjection() {
        assertNotNull(exec);
        assertNotNull(maxAsync2);
        assertNotNull(appProduced);
        assertNotNull(appProduced_injected);
    }

    /**
     * Verify that injected ME instances are useable in a very basic way
     */
    public void testBasicExecutorUsable() throws Exception {
        assertEquals(exec.supplyAsync(() -> "hello").get(5, TimeUnit.SECONDS), "hello");
        assertEquals(maxAsync2.supplyAsync(() -> "hello").get(5, TimeUnit.SECONDS), "hello");
        assertEquals(appProduced.supplyAsync(() -> "hello").get(5, TimeUnit.SECONDS), "hello");
        assertEquals(appProduced_injected.supplyAsync(() -> "hello").get(5, TimeUnit.SECONDS), "hello");
    }

    /**
     * Verify that the @ManagedExecutorConfig annotation is applied to injection points
     */
    public void testMaxAsync2() throws Exception {
        checkMaxAsync2(maxAsync2);
        checkMaxAsync2(appProduced_injected);
    }
    
    private void checkMaxAsync2(ManagedExecutor exec) throws Exception {
        final long MAX_WAIT_NS = TimeUnit.MINUTES.toNanos(2);

        Phaser barrier = new Phaser(2);
        try {
            // Use up both maxAsync slots on blocking operations and wait for them to start
            Future<Integer> future1 = exec.submit(() -> barrier.awaitAdvance(barrier.arriveAndAwaitAdvance()));
            CompletableFuture<Integer> future2 = exec
                    .supplyAsync(() -> barrier.awaitAdvance(barrier.arriveAndAwaitAdvance()));
            barrier.awaitAdvanceInterruptibly(0, MAX_WAIT_NS, TimeUnit.NANOSECONDS);

            // This data structure holds the results of tasks which shouldn't be able to run
            // yet
            LinkedBlockingQueue<String> results = new LinkedBlockingQueue<String>();

            // Submit additional tasks/actions for async execution.
            // These should queue, but otherwise be unable to start yet due to maxAsync=2.
            CompletableFuture<Void> future3 = exec.runAsync(() -> results.offer("Result3"));
            CompletableFuture<Boolean> future4 = exec.supplyAsync(() -> results.offer("Result4"));
            Future<Boolean> future5 = exec.submit(() -> results.offer("Result5"));
            CompletableFuture<Boolean> future6 = exec.completedFuture("6")
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
        }
    }
}
