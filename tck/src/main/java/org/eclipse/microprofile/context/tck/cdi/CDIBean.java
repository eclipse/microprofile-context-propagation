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
package org.eclipse.microprofile.context.tck.cdi;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Qualifier;

import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.context.tck.contexts.label.Label;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.testng.Assert;

@ApplicationScoped
public class CDIBean {
    
    static final long MAX_WAIT_SEC = 120;

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    public @interface AppProducedExecutor {}

    @Inject @AppProducedExecutor
    ManagedExecutor appProduced;

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    public @interface LabelContextPropagator {}

    @Inject @LabelContextPropagator
    ThreadContext labelContextPropagator;

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    public @interface PriorityContext {}

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    public @interface Priority3Executor {}

    @Inject @Priority3Executor
    Executor priority3Executor;

    /**
     * Extra sanity check test to verify injection is occurring. However, if CDI is 
     * set up properly, this bean should not even be reachable if injection fails. 
     */
    public void testVerifyInjection() {
        assertNotNull(appProduced);
    }

    /**
     * Verify that injected ME instances are useable in a very basic way
     *
     * @throws Exception indicates test failure
     */
    public void testBasicExecutorUsable() throws Exception {
        assertEquals(appProduced.supplyAsync(() -> "hello").get(MAX_WAIT_SEC, TimeUnit.SECONDS), "hello");
    }

    /**
     * Application can provide producers of ThreadContext that are qualified.
     */
    public void testAppDefinedProducerOfThreadContext() {
        Assert.assertNotNull(labelContextPropagator,
                "Application should be able to use qualifier to obtain produced instance of ThreadContext.");

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 2 ? 1 : 2;
        try {
            Thread.currentThread().setPriority(newPriority);
            Label.set("testAppDefinedProducerOfThreadContext-label");
            Buffer.set(new StringBuffer("testAppDefinedProducerOfThreadContext-buffer"));

            Runnable testLabelContext = labelContextPropagator.contextualRunnable(() -> {
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
     * Application-defined producer methods can have injection points of ThreadContext.
     */
    public void testAppDefinedProducerUsingInjectedThreadContext() {
        Assert.assertNotNull(priority3Executor,
                "Application should be able to create its own CDI producer that injects a ThreadContext.");

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
}
