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

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Named;

/**
 * Produces beans consumed by MpConfigBean in order to avoid circular dependencies
 */
@ApplicationScoped
public class ProducerBean {

    @Produces
    @ApplicationScoped
    @MPConfigBean.Max5Queue
    protected ManagedExecutor createExecutor() {
        // rely on MP Config for defaults of maxAsync=1, cleared=Remaining
        return ManagedExecutor.builder()
                .maxQueued(5)
                .propagated()
                .build();
    }

    public void shutdown(@Disposes @MPConfigBean.Max5Queue ManagedExecutor executor) {
        executor.shutdown();
    }

    // None of the defaults from MP Config apply because the application explicitly specifies all values
    @Produces
    @ApplicationScoped
    @Named("producedThreadContext")
    protected ThreadContext bufferContext = ThreadContext.builder()
            .propagated(Buffer.CONTEXT_NAME)
            .unchanged()
            .cleared(ThreadContext.ALL_REMAINING)
            .build();
}
