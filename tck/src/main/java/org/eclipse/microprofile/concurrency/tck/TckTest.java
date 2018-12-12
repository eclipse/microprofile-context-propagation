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

import java.util.function.Supplier;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;
import org.eclipse.microprofile.concurrency.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.concurrency.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider;
import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TckTest extends Arquillian {

    @Deployment
    public static WebArchive createDeployment() {
        // build a JAR that provides fake 'ThreadPriority' context type
        JavaArchive threadPriorityContextProvider = ShrinkWrap.create(JavaArchive.class, "threadPriorityContext.jar")
                .addPackage(ThreadPriorityContextProvider.class.getPackage().getName())
                .addAsServiceProvider(ThreadContextProvider.class, ThreadPriorityContextProvider.class);

        // build a JAR that provides two fake context types: 'Buffer' and 'Label'
        JavaArchive multiContextProvider = ShrinkWrap.create(JavaArchive.class, "bufferAndLabelContext.jar")
                .addPackage(Buffer.class.getPackage().getName())
                .addPackage(BufferContextProvider.class.getPackage().getName())
                .addPackage(Label.class.getPackage().getName())
                .addPackage(LabelContextProvider.class.getPackage().getName())
                .addAsServiceProvider(ThreadContextProvider.class, BufferContextProvider.class, LabelContextProvider.class);

        return ShrinkWrap.create(WebArchive.class, TckTest.class.getSimpleName() + ".war")
                .addClass(TckTest.class)
                .addAsLibraries(threadPriorityContextProvider, multiContextProvider);
    }

    @Test
    public void builderForThreadContextIsProvided() {
        Assert.assertNotNull(ThreadContext.builder(),
                "MicroProfile Concurrency implementation does not provide a ThreadContext builder");
    }

    @Test
    public void providerSet() {
        ConcurrencyProvider provider = ConcurrencyProvider.instance();
        Assert.assertNotNull(provider, "ConcurrencyProvider is not set");
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
                Assert.assertEquals(Buffer.get().toString(), "", "Context type that is configured to be cleared was not cleared");
                Assert.assertEquals(Label.get(), "test-label-A", "Context type was not propagated to contextual action");
                return Thread.currentThread().getPriority();
            });

            // Alter the values again
            Buffer.get().append("-and-B");
            Label.set("test-label-B");
            Thread.currentThread().setPriority(priorityB);

            // The contextual action runs with previously captured Label/ThreadPriority context, and with cleared Buffer context
            int priority = contextualSupplier.get();

            Assert.assertEquals(priority, priorityA, "Context type was not propagated to contextual action");

            // The contextual action and its associated thread context snapshot is reusable
            priority = contextualSupplier.get();

            Assert.assertEquals(priority, priorityA, "Context type was not propagated to contextual action");

            // Has context been properly restored after the contextual operation(s)?
            Assert.assertEquals(Buffer.get().toString(), "test-buffer-content-A-and-B",
                    "Previous context was not restored after context was cleared for contextual action");
            Assert.assertEquals(Label.get(), "test-label-B",
                    "Previous context (Label) was not restored after context was propagated for contextual action");
            Assert.assertEquals(Thread.currentThread().getPriority(), priorityB,
                    "Previous context (ThreadPriority) was not restored after context was propagated for contextual action");
        }
        finally {
            // Restore original values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }
}
