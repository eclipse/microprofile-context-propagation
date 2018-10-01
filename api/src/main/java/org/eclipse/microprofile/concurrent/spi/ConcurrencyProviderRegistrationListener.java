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

/**
 * <p><code>ConcurrencyProviderRegistrationListener</code> instances receive
 * notification upon registration of the <code>ConcurrencyProvider</code>.</p>
 *
 * <p>This serves as a convenient invocation point for enabling system wide
 * context propagator hooks.</p>
 *
 * <p>Implementations of <code>ConcurrencyProviderRegistrationListener</code>
 * and related classes are packaged within a third party JAR file, or they can
 * be supplied by the container or MicroProfile Concurrency implementation.
 * <code>ConcurrencyProviderRegistrationListener</code>s are made discoverable
 * via the standard <code>ServiceLoader</code> mechanism. The JAR file that
 * packages it must include a file of the following name and location,</p>
 *
 * <code>META-INF/services/org.eclipse.microprofile.concurrent.spi.ConcurrencyProviderRegistrationListener</code>
 *
 * <p>The content of the aforementioned file must be one or more lines, each
 * specifying the fully qualified name of a
 * <code>ConcurrencyProviderRegistrationListener</code> implementation that is
 * provided within the JAR file.</p>
 *
 * <p><code>ConcurrencyProviderRegistrationListener</code> is an SPI level
 * interface. It must not be supplied directly within applications because
 * application classes might not be available on the <code>ServiceLoader</code>
 * within the scope of the provider registration.</p>
 *
 * <p>Upon successful {@link ConcurrencyProvider#register registration} of the
 * <code>ConcurrencyProvider</code>, the MicroProfile Concurrency
 * implementation must subsequently query the container's
 * <code>ServiceLoader</code> for all
 * <code>ConcurrencyProviderRegistrationListener</code>s and send the
 * <code>providerRegistered</code> notification to each.</p>
 */
public interface ConcurrencyProviderRegistrationListener {
    /**
     * This notification indicates that the <code>ConcurrencyProvider</code>
     * instance has been successfully registered. Implementations may use
     * the supplied provider to create and configure builders and build
     * instances of <code>ManagedExecutor</code> and
     * <code>ThreadContext</code>. 
     *
     * @param provider the <code>ConcurrencyProvider</code> instance that was
     *        successfully registered with the container. 
     */
    void providerRegistered(ConcurrencyProvider provider);
}