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
 * <p>This class gives the container that registered a
 * <code>ConcurrencyProvider</code> exclusive control over unregistering it.
 * </p>
 */
public class ConcurrencyProviderRegistration {
    private final ConcurrencyProvider provider;

    ConcurrencyProviderRegistration(ConcurrencyProvider provider) {
        this.provider = provider;
    }

    /**
     * Unregister the <code>ConcurrencyProvider</code> that is represented by
     * this <code>ConcurrencyProviderRegistration</code> instance.
     */
    public void unregister() {
        ConcurrencyProvider.INSTANCE.compareAndSet(provider, null);
    }
}