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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
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
}
