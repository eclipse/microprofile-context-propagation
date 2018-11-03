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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * <p>Configures an injected <code>ManagedExecutor</code> instance.</p>
 *
 * <p>Example usage:</p>
 * <pre><code> &commat;Inject &commat;ManagedExecutorConfig(propagated=ThreadContext.CDI, maxAsync=5)
 * ManagedExecutor executor;
 * ...
 * </code></pre>
 *
 * <p>A <code>ManagedExecutor</code> must fail to inject, raising
 * {@link javax.enterprise.inject.spi.DeploymentException DeploymentException}
 * on application startup, if more than one provider provides the same thread context
 * {@link org.eclipse.microprofile.concurrent.spi.ThreadContextProvider#getThreadContextType type}.
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface ManagedExecutorConfig {
    /**
     * <p>Defines the set of thread context types to clear from the thread
     * where the action or task executes. The previous context is resumed
     * on the thread after the action or task ends.</p>
     *
     * <p>By default, the transaction context is cleared/suspended from
     * the execution thread so that actions and tasks can start and
     * end transactions of their choosing, to independently perform their
     * own transactional work, as needed.</p>
     *
     * <p>Constants for specifying some of the core context types are provided
     * on {@link ThreadContext}. Other thread context types must be defined
     * by the specification that defines the context type or by a related
     * MicroProfile specification.</p>
     *
     * <p>A <code>ManagedExecutor</code> must fail to inject, raising
     * {@link javax.enterprise.inject.spi.DefinitionException DefinitionException}
     * on application startup,
     * if a context type specified within this set is unavailable
     * or if the {@link #propagated} set includes one or more of the
     * same types as this set.</p>
     */
    String[] cleared() default { ThreadContext.TRANSACTION };

    /**
     * <p>Defines the set of thread context types to capture from the thread
     * that creates a dependent stage (or that submits a task) and which to
     * propagate to the thread where the action or task executes.</p>
     *
     * <p>The default set of propagated thread context types is
     * {@link ThreadContext#ALL_REMAINING}, which includes all available
     * thread context types that support capture and propagation to other
     * threads, except for those that are explicitly {@link cleared},
     * which, by default is {@link ThreadContext#TRANSACTION} context,
     * in which case is suspended from the thread that runs the action or
     * task.</p>
     *
     * <p>Constants for specifying some of the core context types are provided
     * on {@link ThreadContext}. Other thread context types must be defined
     * by the specification that defines the context type or by a related
     * MicroProfile specification.</p>
     *
     * <p>Thread context types which are not otherwise included in this set
     * are cleared from the thread of execution for the duration of the
     * action or task.</p>
     *
     * <p>A <code>ManagedExecutor</code> must fail to inject, raising
     * {@link javax.enterprise.inject.spi.DefinitionException DefinitionException}
     * on application startup,
     * if a context type specified within this set is unavailable
     * or if the {@link #cleared} set includes one or more of the
     * same types as this set.</p>
     */
    String[] propagated() default { ThreadContext.ALL_REMAINING };

    /**
     * <p>Establishes an upper bound on the number of async completion stage
     * actions and async executor tasks that can be running at any given point
     * in time. There is no guarantee that async actions or tasks will start
     * running immediately, even when the <code>maxAsync</code> constraint has
     * not get been reached. Async actions and tasks remain queued until
     * the <code>ManagedExecutor</code> starts executing them.</p>
     *
     * <p>The default value of <code>-1</code> indicates no upper bound,
     * although practically, resource constraints of the system will apply.</p>
     *
     * <p>A <code>ManagedExecutor</code> must fail to inject, raising
     * {@link javax.enterprise.inject.spi.DefinitionException DefinitionException}
     * on application startup, if the
     * <code>maxAsync</code> value is 0 or less than -1.
     */
    int maxAsync() default -1;

    /**
     * <p>Establishes an upper bound on the number of async actions and async tasks
     * that can be queued up for execution. Async actions and tasks are rejected
     * if no space in the queue is available to accept them.</p>
     *
     * <p>The default value of <code>-1</code> indicates no upper bound,
     * although practically, resource constraints of the system will apply.</p>
     *
     * <p>A <code>ManagedExecutor</code> must fail to inject, raising
     * {@link javax.enterprise.inject.spi.DefinitionException DefinitionException}
     * on application startup, if the
     * <code>maxQueued</code> value is 0 or less than -1.
     */
    int maxQueued() default -1;
}