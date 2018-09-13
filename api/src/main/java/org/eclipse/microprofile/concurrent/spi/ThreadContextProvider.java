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
package org.eclipse.microprofile.concurrent.spi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * <p>Third party providers of thread context implement this interface to enable the
 * provided type of context to participate in thread context capture & propagation
 * when the MicroProfile Concurrency specification's <code>ManagedExecutor</code>
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
 * <code>META-INF/services/org.eclipse.microprofile.concurrent.spi.ThreadContextProvider</code>
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
     * <p>This convenience method is provided for product integration code that captures
     * all available thread context types and is looking to avoid boilerplate code in its
     * usage of the SPI.</p>
     *
     * <p>This method locates all available <code>ThreadContextProvider</code>s for the
     * current thread context class loader, detects and rejects duplicate
     * <code>ThreadContextProvider</code> types, and uses the
     * <code>ThreadContextProvider</code>s to capture context from the current thread,
     * or, where capture from the current thread is not supported, obtains default
     * context.</p>
     *
     * @param props provided for compatibility with EE Concurrency spec, which allows
     *        for specifying a set of 'execution properties' when capturing thread context.
     *        Thread context providers that don't supply or use execution properties
     *        can ignore this parameter.
     * @return immutable combined snapshot of all context types, captured from the current
     *         thread or defaulted, and ordered according to their declared context type
     *         prerequisites.
     */
    public static ThreadContextSnapshot captureAllContext(Map<String, String> props) {
        Set<String> contextTypes = new HashSet<String>();
        List<ThreadContextProvider> providers = new ArrayList<ThreadContextProvider>();
        List<ThreadContextProvider> providersWithUnmetPrereqs = new LinkedList<ThreadContextProvider>();

        for (ThreadContextProvider provider : ServiceLoader.load(ThreadContextProvider.class)) {
            String type = provider.getThreadContextType();
            if (contextTypes.contains(type)) {
                throw new Error("Multiple providers of thread context type " + type);
            }
            Set<String> prereqs = provider.getPrerequisites();
            if (prereqs.size() == 0 || contextTypes.containsAll(prereqs)) {
                providers.add(provider);
                contextTypes.add(type);
            }
            else {
                providersWithUnmetPrereqs.add(provider);
            }
        }

        while (providersWithUnmetPrereqs.size() > 0) {
            boolean atLeastOneAdded = false;
            for (Iterator<ThreadContextProvider> it = providersWithUnmetPrereqs.iterator(); it.hasNext(); ) {
                ThreadContextProvider provider = it.next();
                String type = provider.getThreadContextType();
                if (contextTypes.contains(type)) {
                    throw new Error("Multiple providers of thread context type " + type);
                }
                Set<String> prereqs = provider.getPrerequisites();
                if (prereqs.size() == 0 || contextTypes.containsAll(prereqs)) {
                    providers.add(provider);
                    contextTypes.add(type);
                    it.remove();
                }
            }
            if (!atLeastOneAdded) {
                throw new Error("Prerequisites not met for " + providersWithUnmetPrereqs);
            }
        }

        // TODO consider caching the ordered ThreadContextProvider list (computed above) per class loader

        List<ThreadContextSnapshot> contextSnapshots = new ArrayList<ThreadContextSnapshot>();
        for (ThreadContextProvider provider : providers) {
            ThreadContextSnapshot contextSnapshot = provider.currentContext(props);
            contextSnapshots.add(contextSnapshot == null ? provider.defaultContext(props) : contextSnapshot);
        }

        // implementation of multi-ThreadContextSnapshot.begin:
        return () -> {
            final LinkedList<ThreadContextController> appliedContexts = new LinkedList<ThreadContextController>();
            try {
                for (ThreadContextSnapshot contextSnapshot : contextSnapshots) {
                    appliedContexts.addFirst(contextSnapshot.begin());
                }
            }
            catch (RuntimeException | Error x) {
                for (ThreadContextController controller : appliedContexts) {
                    controller.endContext();
                }
                throw x;
            }
            // implementation of multi-ThreadContextController.endContext:
            return () -> {
                for (ThreadContextController controller : appliedContexts) {
                    controller.endContext();
                }
            };
        };
    }

    /**
     * Captures from the current thread a snapshot of the provided thread context type.
     *
     * @param props provided for compatibility with EE Concurrency spec, which allows
     *        for specifying a set of 'execution properties' when capturing thread context.
     *        Thread context providers that don't supply or use execution properties
     *        can ignore this parameter.
     * @return immutable snapshot of the provided type of context, captured from the
     *         current thread. NULL must be returned if the thread context provider
     *         does not support capturing context from the current thread and
     *         propagating it to other threads.
     */
    ThreadContextSnapshot currentContext(Map<String, String> props);

    /**
     * <p>Returns empty/default context of the provided type. This context is not
     * captured from the current thread, but instead represents the behavior that you
     * get for this context type when no particular context has been applied to the
     * thread.</p>
     *
     * <p>This is used in cases where the provided type of thread context should not be
     * propagated from the requesting thread or inherited from the thread of execution,
     * in which case it is necessary to establish an empty/default context in its place,
     * so that an action does not unintentionally inherit context of the thread that
     * happens to run it.</p>
     *
     * <p>For example, a security context provider's empty/default context ensures there
     * is no authenticated user on the thread. A transaction context provider's
     * empty/default context ensures that any active transaction is suspended.
     * And so forth.</p>
     *
     * @param props provided for compatibility with EE Concurrency spec, which allows
     *        for specifying a set of 'execution properties'. Thread context providers
     *        that don't supply or use execution properties can ignore this parameter.
     * @return immutable empty/default context of the provided type.
     */
    ThreadContextSnapshot defaultContext(Map<String, String> props);

    /**
     * <p>Returns an immutable set of thread context type identifiers (refer to
     * <code>getThreadContextType</code>) that the provided type of thread context
     * depends upon.</p>
     *
     * <p>The <code>ManagedExecutor</code> and <code>ThreadContext</code> implementation
     * must ensure that all prerequisite types are established on the thread
     * before invoking the <code>ThreadContextSnapshot.begin</code> method for this type
     * of thread context and must also ensure that the prerequisite types are not removed
     * until after the corresponding <code>ThreadContextController.endContext</code> method.</p>
     *
     * <p>This has the effect of guaranteeing that prerequisite context types are available
     * for the duration of the contextualized action/task as well as during the initial
     * establishment and subsequent removal of thread context.</p>
     *
     * @return immutable set of identifiers for prerequisite thread context types.
     *         Thread context providers that have no prerequisites must return
     *         <code>Collections.EMPTY_SET</code> to indicate this.
     */
    Set<String> getPrerequisites();

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
     * &commat;Inject &commat;ManagedExecutorConfig(context = ThreadContext.CDI)
     * ManagedExecutor executor;
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