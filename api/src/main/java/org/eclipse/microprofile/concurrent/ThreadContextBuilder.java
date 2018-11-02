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
 * <p>Builder for <code>ThreadContext</code> instances.</p>
 *
 * <p>Example usage:</p>
 * <pre><code> ThreadContext threadContext = ThreadContextBuilder.instance()
 *                                                   .propagated(ThreadContext.APPLICATION, ThreadContext.SECURITY)
 *                                                   .unchanged(ThreadContext.TRANSACTION)
 *                                                   .build();
 * ...
 * </code></pre>
 */
public interface ThreadContextBuilder {
    /**
     * <p>Builds a new <code>ThreadContext</code> instance with the
     * configuration that this builder represents as of the point in time when
     * this method is invoked.</p>
     *
     * <p>After <code>build</code> is invoked, the builder instance retains its
     * configuration and may be further updated to represent different
     * configurations and build additional <code>ThreadContext</code>
     * instances.</p>
     *
     * @return new instance of <code>ThreadContext</code>.
     * @throws IllegalStateException for any of the following error conditions
     *         <ul>
     *         <li>if one or more of the same context types appear in multiple
     *         of the following sets:
     *         ({@link #cleared}, {@link #propagated}, {@link #unchanged})</li>
     *         <li>if a thread context type that is configured to be
     *         {@link #cleared} or {@link #propagated} is unavailable</li>
     *         <li>if the direct or indirect
     *         {@link org.eclipse.microprofile.concurrent.spi.ThreadContextProvider#getPrerequisites prerequisites}
     *         of a <code>ThreadContextProvider</code> are unsatisfied</li>
     *         <li>if a <code>ThreadContextProvider</code> has a direct or
     *         indirect prerequisite on itself</li>
     *         <li>if more than one <code>ThreadContextProvider</code> has the
     *         same thread context
     *         {@link org.eclipse.microprofile.concurrent.spi.ThreadContextProvider#getThreadContextType type}
     *         </li>
     *         </ul>
     */
    ThreadContext build();

    /**
     * Creates a new <code>ThreadContextBuilder</code> instance.
     *
     * @return a new <code>ThreadContextBuilder</code> instance.
     */
    public static ThreadContextBuilder instance() {
        return ConcurrencyProvider.instance().newThreadContextBuilder();
    }

    /**
     * <p>Defines the set of thread context types to clear from the thread
     * where the action or task executes. The previous context is resumed
     * on the thread after the action or task ends.</p>
     *
     * <p>This set replaces the <code>cleared</code> set that was
     * previously specified on the builder instance, if any.</p>
     *
     * <p>The default set of cleared thread context types is
     * {@link ThreadContext#TRANSACTION}, which means that a transaction
     * is not active on the thread when the action or task runs, such
     * that each action or task is able to independently start and end
     * its own transactional work.</p>
     *
     * <p>Constants for specifying some of the core context types are provided
     * on {@link ThreadContext}. Other thread context types must be defined
     * by the specification that defines the context type or by a related
     * MicroProfile specification.</p>
     *
     * <p>Inclusion of a thread context type with prerequisites implies
     * inclusion of the prerequisites, even if not explicitly specified.</p>
     *
     * @param types types of thread context to clear from threads that run
     *        actions and tasks.
     * @return the same builder instance upon which this method is invoked.
     */
    ThreadContextBuilder cleared(String... types);

    /**
     * <p>Defines the set of thread context types to capture from the thread
     * that contextualizes an action or task. This context is later
     * re-established on the thread(s) where the action or task executes.</p>
     *
     * <p>This set replaces the <code>propagated</code> set that was
     * previously specified on the builder instance, if any.</p>
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
     * <p>Inclusion of a thread context type with prerequisites implies
     * inclusion of the prerequisites, even if not explicitly specified.</p>
     *
     * <p>Thread context types which are not otherwise included in this set or
     * in the {@link #unchanged} set are cleared from the thread of execution
     * for the duration of the action or task.</p>
     *
     * <p>A <code>ThreadContext</code> must fail to {@link #build} if the same
     * context type is included in this set as well as in the {@link #unchanged}
     * set.</p>
     *
     * @param types types of thread context to capture and propagated.
     * @return the same builder instance upon which this method is invoked.
     */
    ThreadContextBuilder propagated(String... types);

    /**
     * <p>Defines a set of thread context types that are essentially ignored,
     * in that they are neither captured nor are they propagated or cleared
     * from thread(s) that execute the action or task.</p>
     *
     * <p>This set replaces the <code>unchanged</code> set that was previously
     * specified on the builder instance.</p>
     *
     * <p>Constants for specifying some of the core context types are provided
     * on {@link ThreadContext}. Other thread context types must be defined
     * by the specification that defines the context type or by a related
     * MicroProfile specification.</p>
     *
     * <p>The configuration of <code>unchanged</code> context is provided for
     * advanced patterns where it is desirable to leave certain context types
     * on the executing thread.</p>
     *
     * <p>For example, to run under the transaction of the thread of execution,
     * with security context cleared and all other thread contexts propagated:</p>
     * <pre><code> ThreadContext threadContext = ThreadContextBuilder.instance()
     *                                                   .unchanged(ThreadContext.TRANSACTION)
     *                                                   .cleared(ThreadContext.SECURITY)
     *                                                   .propagated(ThreadContext.ALL_REMAINING)
     *                                                   .build();
     * ...
     * task = threadContext.withCurrentContext(new MyTransactionlTask());
     * ...
     * // on another thread,
     * tx.begin();
     * ...
     * task.run(); // runs under the transaction due to 'unchanged'
     * tx.commit();
     * </code></pre>
     *
     * <p>Inclusion of a thread context type with prerequisites implies
     * inclusion of the prerequisites, in that the prequisistes are
     * considered 'unchanged' as well, even if not explicitly specified.</p>
     *
     * <p>A <code>ThreadContext</code> must fail to {@link #build} if the same
     * context type is included in this set as well as in the set specified by
     * {@link #propagated}.</p>
     *
     * @param types types of thread context to leave unchanged on the thread.
     * @return the same builder instance upon which this method is invoked.
     */
    ThreadContextBuilder unchanged(String... types);
}