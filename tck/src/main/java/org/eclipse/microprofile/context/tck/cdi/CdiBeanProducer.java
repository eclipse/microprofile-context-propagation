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

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.context.tck.contexts.label.Label;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import java.util.concurrent.Executor;

import static org.eclipse.microprofile.context.tck.contexts.priority.spi.ThreadPriorityContextProvider.THREAD_PRIORITY;

@ApplicationScoped
public class CdiBeanProducer {

    @Produces
    @ApplicationScoped
    @CDIBean.AppProducedExecutor
    public ManagedExecutor createExec() {
        return ManagedExecutor.builder().cleared(ThreadContext.TRANSACTION).propagated(ThreadContext.ALL_REMAINING).build();
    }

    @Produces
    @ApplicationScoped
    @CDIBean.LabelContextPropagator
    ThreadContext labelContextPropagator1 = ThreadContext.builder().propagated(Label.CONTEXT_NAME)
            .unchanged()
            .cleared(ThreadContext.ALL_REMAINING)
            .build();

    @Produces
    @ApplicationScoped
    @CDIBean.Priority3Executor
    public Executor createPriority3Executor(@CDIBean.PriorityContext ThreadContext ctx) {
        int originalPriority = Thread.currentThread().getPriority();
        try {
            Thread.currentThread().setPriority(3);
            Label.set("do-not-propagate-this-label");
            Buffer.set(new StringBuffer("do-not-propagate-this-buffer"));

            return ctx.currentContextExecutor();
        } finally {
            // restore previous values
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    @Produces
    @ApplicationScoped
    @CDIBean.PriorityContext
    ThreadContext threadPriorityContext = ThreadContext.builder().propagated(THREAD_PRIORITY).build();
}
