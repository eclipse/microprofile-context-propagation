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
package org.eclipse.microprofile.concurrent;

import org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider;

/**
 * <p>Builder for <code>ManagedExecutor</code> instances.</p>
 *
 * <p>Example usage:</p>
 * <pre><code> ManagedExecutor executor = ManagedExecutorBuilder.instance()
 *                                                  .maxAsync(5)
 *                                                  .maxQueued(20)
 *                                                  .propagated(ThreadContext.SECURITY)
 *                                                  .build();
 * ...
 * </code></pre>
 */
public interface ManagedExecutorBuilder {
    /**
     * <p>Builds a new <code>ManagedExecutor</code> with the configuration
     * that this builder represents as of the point in time when this method
     * is invoked.</p>
     *
     * <p>After <code>build</code> is invoked, the builder instance retains its
     * configuration and may be further updated to represent different
     * configurations and build additional <code>ManagedExecutor</code>
     * instances.</p>
     *
     * @return new instance of <code>ManagedExecutor</code>.
     */
    ManagedExecutor build();

    /**
     * <p>Defines the set of thread context types to capture from the thread
     * that creates a dependent stage (or that submits a task) and which to
     * propagate to the thread where the action or task executes.</p>
     *
     * <p>This set replaces the set that was previously specified on the
     * builder instance.</p>
     *
     * <p>The default set of thread context types is those required by the
     * EE Concurrency spec, plus CDI.</p>
     *
     * <p>Constants for specifying some of the core context types are provided
     * on {@link ThreadContext}. Other thread context types must be defined
     * by the specification that defines the context type or by a related
     * MicroProfile specification.</p>
     *
     * <p>Inclusion of a thread context type with prerequisites implies
     * inclusion of the prerequisites, even if not explicitly specified.</p>
     *
     * <p>Thread context types which are not otherwise included in this set
     * are cleared from the thread of execution for the duration of the
     * action or task.</p>
     *
     * @param types types of thread context to capture and propagate.
     * @return the same builder instance upon which this method is invoked.
     */
    ManagedExecutorBuilder propagated(String... types);

    /**
     * Creates a new <code>ManagedExecutorBuilder</code> instance.
     *
     * @return a new <code>ManagedExecutorBuilder</code> instance.
     */
    public static ManagedExecutorBuilder instance() {
        return ConcurrencyProvider.instance().newManagedExecutorBuilder();
    }

    /**
     * <p>Establishes an upper bound on the number of async completion stage
     * actions and async executor tasks that can be running at any given point
     * in time. Async actions and tasks remain queued until capacity is available
     * to execute them.</p>
     *
     * <p>The default value of <code>-1</code> indicates no upper bound,
     * although practically, resource constraints of the system will apply.</p>
     *
     * @param max upper bound on async completion stage actions and executor tasks.
     * @return the same builder instance upon which this method is invoked.
     * @throws IllegalArgumentException if 0 or less than -1.
     */
    ManagedExecutorBuilder maxAsync(int max);

    /**
     * <p>Establishes an upper bound on the number of async actions and async tasks
     * that can be queued up for execution. Async actions and tasks are rejected
     * if no space in the queue is available to accept them.</p>
     *
     * <p>The default value of <code>-1</code> indicates no upper bound,
     * although practically, resource constraints of the system will apply.</p>
     *
     * @param max upper bound on async actions and tasks that can be queued.
     * @return the same builder instance upon which this method is invoked.
     * @throws IllegalArgumentException if 0 or less than -1.
     */
    ManagedExecutorBuilder maxQueued(int max);
}