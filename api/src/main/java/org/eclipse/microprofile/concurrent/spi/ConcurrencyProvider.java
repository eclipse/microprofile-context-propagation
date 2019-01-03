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

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>MicroProfile Concurrency provider implementation supplied by the
 * container, which creates and caches instances of
 * <code>ConcurrencyManager</code> per class loader,
 * which in turn create new instances of
 * {@link org.eclipse.microprofile.concurrent.ManagedExecutor.Builder ManagedExecutor.Builder} and
 * {@link org.eclipse.microprofile.concurrent.ThreadContext.Builder ThreadContext.Builder}.</p>
 *
 * <p>The container must register its <code>ConcurrencyProvider</code>
 * implementation via the <code>register</code> method, or by providing 
 * an implementation via the standard {@link ServiceLoader} mechanism.</p>
 * 
 * <p><code>ConcurrencyProvider</code> implementations that wish to use
 * the {@link ServiceLoader}  registration mechanism must include a file 
 * of the following name and location in their jar:</p>
 *
 * <code>META-INF/services/org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider</code>
 *
 * <p>The content of the aforementioned file must be exactly one line, specifying
 * the fully qualified name of a <code>ConcurrencyProvider</code> implementation
 * that is provided within the JAR file.</p>
 * 
 * <p>If there is no manually registered <code>ConcurrencyProvider</code> (via
 * {@link #register(ConcurrencyProvider)}), any call to {@link #instance()} will
 * look up any <code>ConcurrencyProvider</code> implementation via the aforementioned
 * {@link ServiceLoader} mechanism. If there are more than one such implementation
 * registered, the {@link #instance()} method will throw an exception as documented</p>
 */
public interface ConcurrencyProvider {
    static AtomicReference<ConcurrencyProvider> INSTANCE = new AtomicReference<ConcurrencyProvider>();

    /**
     * Obtains the <code>ConcurrencyProvider</code> instance that has been previously registered, or
     * uses {@link ServiceLoader} to load and register a <code>ConcurrencyProvider</code> from the
     * current context class loader.
     *
     * @return the registered <code>ConcurrencyProvider</code> instance.
     * @throws IllegalStateException if there are no registered <code>ConcurrencyProvider</code> and
     *      we could not discover any via {@link ServiceLoader}, or if there are more than one
     *      {@link ServiceLoader} results.
     */
    public static ConcurrencyProvider instance() {
        ConcurrencyProvider provider = INSTANCE.get();
        if (provider == null) {
            for (ConcurrencyProvider serviceProvider : ServiceLoader.load(ConcurrencyProvider.class)) {
                if (INSTANCE.compareAndSet(null, serviceProvider)) {
                    provider = serviceProvider;
                }
                else {
                    throw new IllegalStateException("ConcurrencyProvider already set");
                }
            }
            if (provider == null) {
                throw new IllegalStateException("Container has not registered a ConcurrencyProvider");
            }
        }
        return provider;
    }

    /**
     * Allows the container to register the <code>ConcurrencyProvider</code>
     * implementation. At most one implementation can be registered at any
     * given point in time. In order to register a different implementation,
     * the container must first unregister its previous implementation.
     *
     * @param provider the provider implementation to register.
     * @throws IllegalStateException if an implementation is already registered.
     */
    public static ConcurrencyProviderRegistration register(ConcurrencyProvider provider) throws IllegalStateException {
        if (INSTANCE.compareAndSet(null, provider)) {
            return new ConcurrencyProviderRegistration(provider); 
        }
        else {
            throw new IllegalStateException("A ConcurrencyProvider implementation has already been registered.");
        }
    }
    
    /**
     * Gets a {@link ConcurrencyManager} for the current thread-context {@link ClassLoader}. This
     * is equivalent to calling <code>getConcurrencyManager(Thread.currentThread().getContextClassLoader())</code>,
     * which is the default implementation of this method.
     * 
     * @return a {@link ConcurrencyManager} for the current thread-context {@link ClassLoader}.
     * @throws IllegalStateException if more than one {@link ThreadContextProvider}
     *         provides the same thread context {@link ThreadContextProvider#getThreadContextType type}
     * @see #getConcurrencyManager(ClassLoader)
     */
    public default ConcurrencyManager getConcurrencyManager() {
        ClassLoader loader = System.getSecurityManager() == null
            ? Thread.currentThread().getContextClassLoader()
            : AccessController.doPrivileged((PrivilegedAction<ClassLoader>) () -> Thread.currentThread().getContextClassLoader());
        return getConcurrencyManager(loader);
    }

    /**
     * Gets a {@link ConcurrencyManager} for the given {@link ClassLoader}. If there is already
     * a {@link ConcurrencyManager} registered for the given {@link ClassLoader} or the concurrency provider
     * uses a single fixed set of {@link ThreadContextProvider} regardless of the class loader, the existing
     * instance will be returned. If not, one will be created, either by provider-specific mechanisms
     * if {@link ConcurrencyManager.Builder} is not supported, or with a {@link ConcurrencyManager.Builder}
     * using the specified {@link ClassLoader} (with {@link ConcurrencyManager.Builder#forClassLoader(ClassLoader)})
     * and with {@link ConcurrencyManager.Builder#addDiscoveredThreadContextProviders()} called in
     * order to load all {@link ThreadContextProvider} discoverable from the given {@link ClassLoader}.
     * If created, the new {@link ConcurrencyManager} will then be registered for the given {@link ClassLoader}
     * with {@link #registerConcurrencyManager(ConcurrencyManager, ClassLoader)}.
     *
     * @param classloader the class loader for which to obtain the concurrency manager.
     * @return a {@link ConcurrencyManager} for the given {@link ClassLoader}.
     * @throws IllegalStateException if more than one {@link ThreadContextProvider}
     *         provides the same thread context {@link ThreadContextProvider#getThreadContextType type}
     * @see ConcurrencyManager.Builder#addDiscoveredThreadContextProviders()
     * @see ConcurrencyManager.Builder#build()
     * @see #registerConcurrencyManager(ConcurrencyManager, ClassLoader)
     */
    public ConcurrencyManager getConcurrencyManager(ClassLoader classLoader);

    /**
     * Returns a new {@link ConcurrencyManager.Builder} to create new {@link ConcurrencyManager}
     * instances. Watch out that instances created this way will not be automatically registered
     * here, so you need to call {@link #registerConcurrencyManager(ConcurrencyManager, ClassLoader)}
     * yourself if you need to.
     * 
     * @return a new {@link ConcurrencyManager.Builder}
     * @throws UnsupportedOperationException if the <code>ConcurrencyProvider</code>
     *         always uses the same set of <code>ThreadContextProvider</code>
     *         or is inseparable from the container.
     */
    public default ConcurrencyManager.Builder getConcurrencyManagerBuilder() {
        throw new UnsupportedOperationException();
    }
    
    /**
     * Registers the given {@link ConcurrencyManager} for the given {@link ClassLoader}, so that
     * further calls to {@link #getConcurrencyManager(ClassLoader)} for the same {@link ClassLoader}
     * will return this instance instead of creating a new one.
     * 
     * @param manager The {@link ConcurrencyManager} to register
     * @param classLoader The {@link ClassLoader} to register it for
     * @throws UnsupportedOperationException if the <code>ConcurrencyProvider</code>
     *         always uses the same set of <code>ThreadContextProvider</code>
     *         or is inseparable from the container.
     * @see #getConcurrencyManager(ClassLoader)
     * @see #releaseConcurrencyManager(ConcurrencyManager)
     */
    public default void registerConcurrencyManager(ConcurrencyManager manager, ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    /**
     * Releases a {@link ConcurrencyManager} that was previously registered with 
     * {@link #registerConcurrencyManager(ConcurrencyManager, ClassLoader)}.
     * 
     * @param manager The {@link ConcurrencyManager} to release
     * @throws UnsupportedOperationException if the <code>ConcurrencyProvider</code>
     *         always uses the same set of <code>ThreadContextProvider</code>
     *         or is inseparable from the container.
     * @see #registerConcurrencyManager(ConcurrencyManager, ClassLoader)
     */
    public default void releaseConcurrencyManager(ConcurrencyManager manager) {
        throw new UnsupportedOperationException();
    }
}