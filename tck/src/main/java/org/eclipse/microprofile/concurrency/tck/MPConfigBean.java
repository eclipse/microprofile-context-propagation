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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Qualifier;

import org.eclipse.microprofile.concurrency.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrency.tck.contexts.label.Label;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ThreadContext;

@ApplicationScoped
public class MPConfigBean {
    protected CompletableFuture<Integer> completedFuture;

    protected Executor contextSnapshot;

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    public @interface Max5Queue {}

    @Produces @ApplicationScoped @Max5Queue
    protected ManagedExecutor createExecutor() {
        // rely on MP Config for defaults of maxAsync=1, cleared=Remaining
        return ManagedExecutor.builder()
                              .maxQueued(5)
                              .propagated()
                              .build();
    }

    public void shutdown(@Disposes @Max5Queue ManagedExecutor executor) {
        executor.shutdown();
    }

    // None of the defaults from MP Config apply because the application explicitly specifies all values
    @Produces @ApplicationScoped @Named("producedThreadContext")
    protected ThreadContext bufferContext = ThreadContext.builder()
                            .propagated(Buffer.CONTEXT_NAME)
                            .unchanged()
                            .cleared(ThreadContext.ALL_REMAINING)
                            .build();

    @Inject
    protected void setCompletedFuture(@Max5Queue ManagedExecutor executor) {
        completedFuture = executor.completedFuture(100);
    }

    @Inject
    protected void setContextSnapshot(@Named("producedThreadContext") ThreadContext contextPropagator) {
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
}
