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

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>A provider implementation supplied by the
 * container, which creates and caches instances of
 * <code>ContextManager</code> per class loader,
 * which in turn create new instances of
 * {@link org.eclipse.microprofile.context.ManagedExecutor.Builder ManagedExecutor.Builder} and
 * {@link org.eclipse.microprofile.context.ThreadContext.Builder ThreadContext.Builder}.</p>
 *
 * <p>The container must register its <code>ContextManagerProvider</code>
 * implementation via the <code>register</code> method, or by providing 
 * an implementation via the standard {@link ServiceLoader} mechanism.</p>
 * 
 * <p><code>ContextManagerProvider</code> implementations that wish to use
 * the {@link ServiceLoader}  registration mechanism must include a file 
 * of the following name and location in their jar:</p>
 *
 * <code>META-INF/services/org.eclipse.microprofile.context.spi.ContextManagerProvider</code>
 *
 * <p>The content of the aforementioned file must be exactly one line, specifying
 * the fully qualified name of a <code>ContextManagerProvider</code> implementation
 * that is provided within the JAR file.</p>
 * 
 * <p>If there is no manually registered <code>ContextManagerProvider</code> (via
 * {@link #register(ContextManagerProvider)}), any call to {@link #instance()} will
 * look up any <code>ContextManagerProvider</code> implementation via the aforementioned
 * {@link ServiceLoader} mechanism. If there are more than one such implementation
 * registered, the {@link #instance()} method will throw an exception as documented</p>
 */
public interface ContextManagerProvider {
    static AtomicReference<ContextManagerProvider> INSTANCE = new AtomicReference<ContextManagerProvider>();

    /**
     * Obtains the <code>ContextManagerProvider</code> instance that has been previously registered, or
     * uses {@link ServiceLoader} to load and register a <code>ContextManagerProvider</code> from the
     * current context class loader.
     *
     * @return the registered <code>ContextManagerProvider</code> instance.
     * @throws IllegalStateException if there are no registered <code>ContextManagerProvider</code> and
     *      we could not discover any via {@link ServiceLoader}, or if there are more than one
     *      {@link ServiceLoader} results.
     */
    public static ContextManagerProvider instance() {
        ContextManagerProvider provider = INSTANCE.get();
        if (provider == null) {
            for (ContextManagerProvider serviceProvider : ServiceLoader.load(ContextManagerProvider.class)) {
                if (INSTANCE.compareAndSet(null, serviceProvider)) {
                    provider = serviceProvider;
                }
                else {
                    throw new IllegalStateException("ContextManagerProvider already set");
                }
            }
            if (provider == null) {
                throw new IllegalStateException("Container has not registered a ContextManagerProvider");
            }
        }
        return provider;
    }

    /**
     * Allows the container to register the <code>ContextManagerProvider</code>
     * implementation. At most one implementation can be registered at any
     * given point in time. In order to register a different implementation,
     * the container must first unregister its previous implementation.
     *
     * @param provider the provider implementation to register.
     * @throws IllegalStateException if an implementation is already registered.
     */
    public static ContextManagerProviderRegistration register(ContextManagerProvider provider) throws IllegalStateException {
        if (INSTANCE.compareAndSet(null, provider)) {
            return new ContextManagerProviderRegistration(provider); 
        }
        else {
            throw new IllegalStateException("A ContextManagerProvider implementation has already been registered.");
        }
    }
    
    /**
     * Gets a {@link ContextManager} for the current thread-context {@link ClassLoader}. This
     * is equivalent to calling <code>getContextManager(Thread.currentThread().getContextClassLoader())</code>,
     * which is the default implementation of this method.
     * 
     * @return a {@link ContextManager} for the current thread-context {@link ClassLoader}.
     * @throws IllegalStateException if more than one {@link ThreadContextProvider}
     *         provides the same thread context {@link ThreadContextProvider#getThreadContextType type}
     * @see #getContextManager(ClassLoader)
     */
    public default ContextManager getContextManager() {
        ClassLoader loader = System.getSecurityManager() == null
            ? Thread.currentThread().getContextClassLoader()
            : AccessController.doPrivileged((PrivilegedAction<ClassLoader>) () -> Thread.currentThread().getContextClassLoader());
        return getContextManager(loader);
    }

    /**
     * Gets a {@link ContextManager} for the given {@link ClassLoader}. If there is already
     * a {@link ContextManager} registered for the given {@link ClassLoader} or the context manager provider
     * uses a single fixed set of {@link ThreadContextProvider} regardless of the class loader, the existing
     * instance will be returned. If not, one will be created, either by provider-specific mechanisms
     * if {@link ContextManager.Builder} is not supported, or with a {@link ContextManager.Builder}
     * using the specified {@link ClassLoader} (with {@link ContextManager.Builder#forClassLoader(ClassLoader)})
     * and with {@link ContextManager.Builder#addDiscoveredThreadContextProviders()} called in
     * order to load all {@link ThreadContextProvider} discoverable from the given {@link ClassLoader}.
     * If created, the new {@link ContextManager} will then be registered for the given {@link ClassLoader}
     * with {@link #registerContextManager(ContextManager, ClassLoader)}.
     *
     * @param classloader the class loader for which to obtain the context manager.
     * @return a {@link ContextManager} for the given {@link ClassLoader}.
     * @throws IllegalStateException if more than one {@link ThreadContextProvider}
     *         provides the same thread context {@link ThreadContextProvider#getThreadContextType type}
     * @see ContextManager.Builder#addDiscoveredThreadContextProviders()
     * @see ContextManager.Builder#build()
     * @see #registerContextManager(ContextManager, ClassLoader)
     */
    public ContextManager getContextManager(ClassLoader classLoader);

    /**
     * Returns a new {@link ContextManager.Builder} to create new {@link ContextManager}
     * instances. Watch out that instances created this way will not be automatically registered
     * here, so you need to call {@link #registerContextManager(ContextManager, ClassLoader)}
     * yourself if you need to.
     * 
     * @return a new {@link ContextManager.Builder}
     * @throws UnsupportedOperationException if the <code>ContextManagerProvider</code>
     *         always uses the same set of <code>ThreadContextProvider</code>
     *         or is inseparable from the container.
     */
    public default ContextManager.Builder getContextManagerBuilder() {
        throw new UnsupportedOperationException();
    }
    
    /**
     * Registers the given {@link ContextManager} for the given {@link ClassLoader}, so that
     * further calls to {@link #getContextManager(ClassLoader)} for the same {@link ClassLoader}
     * will return this instance instead of creating a new one.
     * 
     * @param manager The {@link ContextManager} to register
     * @param classLoader The {@link ClassLoader} to register it for
     * @throws UnsupportedOperationException if the <code>ContextManagerProvider</code>
     *         always uses the same set of <code>ThreadContextProvider</code>
     *         or is inseparable from the container.
     * @see #getContextManager(ClassLoader)
     * @see #releaseContextManager(ContextManager)
     */
    public default void registerContextManager(ContextManager manager, ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    /**
     * Releases a {@link ContextManager} that was previously registered with 
     * {@link #registerContextManager(ContextManager, ClassLoader)}.
     * 
     * @param manager The {@link ContextManager} to release
     * @throws UnsupportedOperationException if the <code>ContextManagerProvider</code>
     *         always uses the same set of <code>ThreadContextProvider</code>
     *         or is inseparable from the container.
     * @see #registerContextManager(ContextManager, ClassLoader)
     */
    public default void releaseContextManager(ContextManager manager) {
        throw new UnsupportedOperationException();
    }
}