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
 * <p>Configures an injected <code>ThreadContext</code> instance.</p>
 *
 * <p>Example usage:</p>
 * <pre><code> &commat;Inject &commat;ThreadContextConfig(ThreadContext.CDI)
 * ThreadContext threadContext;
 * ...
 * </code></pre>
 *
 * <p>A <code>ThreadContext</code> must fail to inject, raising
 * <code>DeploymentException</code> on application startup,
 * if the direct or indirect
 * {@link org.eclipse.microprofile.concurrent.spi.ThreadContextProvider#getPrerequisites prerequisites}
 * of a <code>ThreadContextProvider</code> are unsatisfied,
 * or a provider has itself as a direct or indirect prerequisite,
 * or if more than one provider provides the same thread context
 * {@link org.eclipse.microprofile.concurrent.spi.ThreadContextProvider#getThreadContextType type}.
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface ThreadContextConfig {
    /**
     * <p>Defines the set of thread context types to capture from the thread
     * that contextualizes an action or task. This context is later
     * re-established on the thread(s) where the action or task executes.</p>
     *
     * <p>The default set of thread context types is
     * {@link ThreadContext#DEFAULTS}, which includes all available
     * thread context types that support capture and propagation to other
     * threads, except for {@link ThreadContext#TRANSACTION} context, which
     * is instead cleared (suspended) from the thread that runs the action or
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
     * <p>A <code>ThreadContext</code> must fail to inject, raising
     * <code>DefinitionException</code> on application startup, if the same
     * context type is included in this set as well as in the {@link #unchanged}
     * set.</p>
     */
    String[] value() default { ThreadContext.DEFAULTS };

    /**
     * <p>Defines a set of thread context types that are essentially ignored,
     * in that they are neither captured nor are they propagated or cleared
     * from thread(s) that execute the action or task.</p>
     *
     * <p>Constants for specifying some of the core context types are provided
     * on {@link ThreadContext}. Other thread context types must be defined
     * by the specification that defines the context type or by a related
     * MicroProfile specification.
     *
     * <p>The configuration <code>unchanged</code> context is provided for
     * advanced patterns where it is desirable to leave certain context types
     * on the executing thread.</p>
     *
     * <p>For example, to run under the transaction of the thread of execution:</p>
     * <pre><code> &commat;Inject &commat;ThreadContextConfig(unchanged = ThreadContext.TRANSACTION)
     * ThreadContext threadContext;
     * ...
     * task = threadContext.withCurrentContext(new MyTransactionalTask());
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
     * <p>A <code>ThreadContext</code> must fail to inject, raising
     * <code>DefinitionException</code> on application startup, if the same
     * context type is included in this set as well as in the set specified by
     * {@link ThreadContextConfig#value}, or if {@link ThreadContext#ALL} is
     * included in the <code>unchanged</code> context because the latter would
     * otherwise render the <code>ThreadContext</code> instance meaningless.
     * </p>
     */
    String[] unchanged() default {};
}