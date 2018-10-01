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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.concurrent.ManagedExecutorBuilder;
import org.eclipse.microprofile.concurrent.ThreadContextBuilder;

/**
 * <p>MicroProfile Concurrency provider implementation supplied by the
 * container, which creates new instances of
 * <code>ManagedExecutorBuilder</code> and
 * <code>ThreadContextBuilder</code>.</p>
 *
 * <p>The container must register its <code>ConcurrencyProvider</code>
 * implementation via the <code>register</code> method.</p>
 */
public interface ConcurrencyProvider {
    static AtomicReference<ConcurrencyProvider> INSTANCE = new AtomicReference<ConcurrencyProvider>();

    /**
     * Creates a new <code>ManagedExecutorBuilder</code> instance.
     *
     * @return a new <code>ManagedExecutorBuilder</code> instance.
     */
    public static ConcurrencyProvider instance() {
        ConcurrencyProvider provider = INSTANCE.get();
        if (provider == null) {
            throw new IllegalStateException("Container has not registered a ConcurrencyProvider");
        }
        return provider;
    }

    /**
     * Creates a new <code>ManagedExecutorBuilder</code> instance.
     *
     * @return a new <code>ManagedExecutorBuilder</code> instance.
     */
    ManagedExecutorBuilder newManagedExecutorBuilder();

    /**
     * Creates a new <code>ThreadContextBuilder</code> instance.
     *
     * @return a new <code>ThreadContextBuilder</code> instance.
     */
    ThreadContextBuilder newThreadContextBuilder(); 

    /**
     * <p>Allows the container to register the <code>ConcurrencyProvider</code>
     * implementation. At most one implementation can be registered at any
     * given point in time. In order to register a different implementation,
     * the container must first unregister its previous implementation.</p>
     *
     * <p>Upon successful registration of the <code>ConcurrencyProvider</code>,
     * the MicroProfile Concurrency implementation must subsequently query the
     * container's <code>ServiceLoader</code> for all
     * <code>ConcurrencyProviderRegistrationListener</code>s and send the
     * <code>providerRegistered</code> notification to each.
     * <code>ConcurrencyProviderRegistrationListener</code> is an SPI level
     * interface. It must not be supplied directly within applications because
     * application classes might not be available on the
     * <code>ServiceLoader</code> within the scope of the provider
     * registration.</p>
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
}