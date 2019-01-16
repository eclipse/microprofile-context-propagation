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

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.ThreadContext;

@ApplicationScoped
public class MPConfigBean {
    protected CompletableFuture<Integer> completedFuture;

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

    // microprofile-config.properties overrides this with maxAsync=1
    @Produces @ApplicationScoped @NamedInstance("producedExecutor")
    protected ManagedExecutor createExecutor(@ManagedExecutorConfig(maxQueued = 5) ManagedExecutor exec) {
        return exec;
    }

    // microprofile-config.properties overrides executor's config with maxAsync=1; maxQueued=2; propagated=Buffer,Label; cleared=Remaining
    @Inject
    protected void setCompletedFuture(ManagedExecutor executor) {
        completedFuture = executor.completedFuture(100);
    }

    public CompletableFuture<Integer> getCompletedFuture() {
        return completedFuture;
    }

    public ManagedExecutor getExecutorWithConfig() {
        return executorWithConfig;
    }
}
