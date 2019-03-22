/*
 * Copyright (c) 2018,2019 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.context.spi;

import java.util.Map;

/**
 * <p>Third party providers of thread context implement this interface to enable the
 * provided type of context to participate in thread context capture & propagation
 * when the <code>ManagedExecutor</code>
 * and <code>ThreadContext</code> are used to create and contextualize dependent
 * actions and tasks.</p>
 *
 * <p>Application code must never access the classes within this <code>spi</code>
 * package. Instead, application code uses the <code>ManagedExecutor</code> and
 * <code>ThreadContext</code> interfaces.</p>
 *
 * <p>The <code>ThreadContextProvider</code> implementation and related classes are
 * packaged within the third party provider's JAR file. The implementation is made
 * discoverable via the standard <code>ServiceLoader</code> mechanism. The JAR file
 * that packages it must include a file of the following name and location,</p>
 *
 * <code>META-INF/services/org.eclipse.microprofile.context.spi.ThreadContextProvider</code>
 *
 * <p>The content of the aforementioned file must be one or more lines, each specifying
 * the fully qualified name of a <code>ThreadContextProvider</code> implementation
 * that is provided within the JAR file.</p>
 *
 * <p><code>ManagedExecutor</code> and <code>ThreadContext</code> must use the
 * <code>ServiceLoader</code> to identify all available implementations of
 * <code>ThreadContextProvider</code> that can participate in thread context capture
 * & propagation and must invoke them either to capture current thread context or establish
 * default thread context per the configuration of the <code>ManagedExecutor</code> or
 * <code>ThreadContext</code> instance wherever these interfaces are used to create
 * and contextualize dependent actions and tasks.</p>
 */
public interface ThreadContextProvider {
    /**
     * Captures from the current thread a snapshot of the provided thread context type.
     *
     * @param props provided for compatibility with EE Concurrency spec, which allows
     *        for specifying a set of 'execution properties' when capturing thread context.
     *        Thread context providers that don't supply or use execution properties
     *        can ignore this parameter.
     * @return immutable snapshot of the provided type of context, captured from the
     *         current thread.
     * @throws IllegalStateException the {@link org.eclipse.microprofile.context.ThreadContext#TRANSACTION Transaction}
     *         context provider may raise this exception
     *         if it chooses not to support the optional capability of propagating Transaction
     *         context across threads. This exception flows back to the application when the
     *         application invokes an operation that captures context, such as
     *         {@link org.eclipse.microprofile.context.ManagedExecutor#runAsync(Runnable) runAsync},
     *         {@link org.eclipse.microprofile.context.ThreadContext#withContextCapture(java.util.concurrent.CompletableFuture)
     *         withContextCapture}, and
     *         {@link org.eclipse.microprofile.context.ThreadContext#contextualFunction(java.util.function.Function) contextualFunction}.
     */
    ThreadContextSnapshot currentContext(Map<String, String> props);

    /**
     * <p>Returns empty/cleared context of the provided type. This context is not
     * captured from the current thread, but instead represents the behavior that you
     * get for this context type when no particular context has been applied to the
     * thread.</p>
     *
     * <p>This is used in cases where the provided type of thread context should not be
     * propagated from the requesting thread or inherited from the thread of execution,
     * in which case it is necessary to establish an empty/cleared context in its place,
     * so that an action does not unintentionally inherit context of the thread that
     * happens to run it.</p>
     *
     * <p>For example, a security context provider's empty/cleared context ensures there
     * is no authenticated user on the thread. A transaction context provider's
     * empty/cleared context ensures that any active transaction is suspended.
     * And so forth.</p>
     *
     * @param props provided for compatibility with EE Concurrency spec, which allows
     *        for specifying a set of 'execution properties'. Thread context providers
     *        that don't supply or use execution properties can ignore this parameter.
     * @return immutable empty/default context of the provided type.
     */
    ThreadContextSnapshot clearedContext(Map<String, String> props);

    /**
     * <p>Returns a human readable identifier for the type of thread context that is
     * captured by this <code>ThreadContextProvider</code> implementation.</p>
     *
     * <p>To ensure portability of applications, this will typically be a keyword that
     * is defined by the same specification that defines the thread context type,
     * or by a related MicroProfile specification.</p>
     *
     * <p>The <code>ThreadContext</code> interface defines constants for some commonly
     * used thread context types, including Application, Security, Transaction,
     * and CDI.</p>
     *
     * <p>The application can use the values documented for the various thread context
     * types when configuring a <code>ManagedExecutor</code> or <code>ThreadContext</code>
     * to capture & propagate only specific types of thread context.</p>
     *
     * <p>For example:</p>
     * <pre><code>
     * ManagedExecutor executor = ManagedExecutor.builder()
     *                                           .propagated(ThreadContext.CDI)
     *                                           .cleared(ThreadContext.ALL_REMAINING)
     *                                           .build();
     * </code></pre>
     *
     * <p>It is an error for multiple thread context providers of identical type to be
     * simultaneously available (for example, two providers of <code>CDI</code> context
     * found on the <code>ServiceLoader</code>). If this is found to be the case,
     * <code>ManagedExecutor</code> and <code>ThreadContext</code> must fail to inject.</p>
     *
     * @return identifier for the provided type of thread context.
     */
    String getThreadContextType();
}