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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.ThreadContextConfig;

@ApplicationScoped
public class MPConfigBean {
    protected CompletableFuture<Integer> completedFuture;

    protected Executor contextSnapshot;

    // microprofile-config.properties overrides this with maxAsync=2; maxQueued=3; propagated=Label; cleared=Remaining
    @Inject @ManagedExecutorConfig(
            maxAsync = 5,
            maxQueued = 20,
            propagated = Buffer.CONTEXT_NAME,
            cleared = Label.CONTEXT_NAME)
    protected ManagedExecutor executorWithConfig;

    // microprofile-config.properties overrides this with maxQueued=4; cleared=ThreadPriority,Buffer,Transaction; propagated=Remaining
    @Inject @NamedInstance("namedExecutor") @ManagedExecutorConfig(
            maxAsync = 1,
            propagated = { Buffer.CONTEXT_NAME, Label.CONTEXT_NAME },
            cleared = ThreadContext.ALL_REMAINING)
    protected ManagedExecutor namedExecutorWithConfig;

    // microprofile-config.properties overrides this with propagated=Label,Buffer
    @Inject @NamedInstance("namedThreadContext") @ThreadContextConfig(
            propagated = Buffer.CONTEXT_NAME,
            cleared = {},
            unchanged = ThreadContext.ALL_REMAINING)
    protected ThreadContext namedThreadContextWithConfig;

    // microprofile-config.properties overrides this with propagated=<unconfigured>; cleared=Buffer,ThreadPriority; unchanged=Remaining
    @Inject
    protected ThreadContext threadContext;

    // microprofile-config.properties overrides exec parameter with maxAsync=1
    @Produces @ApplicationScoped @NamedInstance("producedExecutor")
    protected ManagedExecutor createExecutor(@ManagedExecutorConfig(maxQueued = 5) ManagedExecutor exec) {
        return exec;
    }

    // microprofile-config.properties overrides threadContext parameter with propagated=Label
    @Produces @ApplicationScoped @Named("producedThreadContext") // it's valid for user-supplied producers to use other qualifiers, such as Named
    protected ThreadContext createThreadContext(
            @NamedInstance("producedExecutor") ManagedExecutor unused1, // this is here so that we can force parameter position 3 to be used
            @NamedInstance("namedExecutor") ManagedExecutor unused2, // this is here so that we can force parameter position 3 to be used
            @ThreadContextConfig(propagated=Buffer.CONTEXT_NAME, cleared=ThreadContext.ALL_REMAINING) ThreadContext threadContext) {
        return threadContext;
    }

    // microprofile-config.properties overrides executor's config with maxAsync=1; maxQueued=2; propagated=Buffer,Label; cleared=Remaining
    @Inject
    protected void setCompletedFuture(ManagedExecutor executor) {
        completedFuture = executor.completedFuture(100);
    }

    // microprofile-config.properties overrides thread context config with propagated=Label; cleared=Remaining; unchanged=ThreadPriority
    @Inject
    protected void setContextSnapshot(@ThreadContextConfig(propagated = { Label.CONTEXT_NAME, Buffer.CONTEXT_NAME },
                                                           cleared = {},
                                                           unchanged = ThreadContext.ALL_REMAINING)
                                      ThreadContext contextPropagator) {

        int originalPriority = Thread.currentThread().getPriority();
        int newPriority = originalPriority == 4 ? 3 : 4;
        Thread.currentThread().setPriority(newPriority);
        Label.set("setContextSnapshot-test-label");
        Buffer.set(new StringBuffer("setContextSnapshot-test-buffer"));
        try {
            contextSnapshot = contextPropagator.currentContextExecutor();
        }
        finally {
            Buffer.set(null);
            Label.set(null);
            Thread.currentThread().setPriority(originalPriority);
        }
    }

    public CompletableFuture<Integer> getCompletedFuture() {
        return completedFuture;
    }

    public Executor getContextSnapshot() {
        return contextSnapshot;
    }

    public ManagedExecutor getExecutorWithConfig() {
        return executorWithConfig;
    }

    public ThreadContext getThreadContext() {
        return threadContext;
    }
}
