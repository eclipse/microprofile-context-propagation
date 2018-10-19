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
 * Use this class to configure instances of {@link ConcurrencyManager}.
 */
public interface ConcurrencyManagerBuilder {
    
    /**
     * Use the specified {@link ThreadContextProvider} instances.
     * @param providers the {@link ThreadContextProvider} instances to use.
     * @return this builder
     */
    public ConcurrencyManagerBuilder withThreadContextProviders(ThreadContextProvider... providers);

    /**
     * Load all discoverable {@link ThreadContextProvider} instances via the {@link ServiceLoader}
     * mechanism on the current thread-context {@link ClassLoader} (unless overridden by {@link #forClassLoader(ClassLoader)}).
     * 
     * @return this builder
     */
    public ConcurrencyManagerBuilder addDiscoveredThreadContextProviders();

    /**
     * Use the given {@link ClassLoader} for {@link #addDiscoveredThreadContextProviders()} instead
     * of the current thread-context {@link ClassLoader}.
     * 
     * @param classLoader the {@link ClassLoader} to use for {@link #addDiscoveredThreadContextProviders()}
     * @return this builder
     */
    public ConcurrencyManagerBuilder forClassLoader(ClassLoader classLoader);
    
    /**
     * Creates a new {@link ConcurrencyManager} with the specified configuration.
     * @return a new {@link ConcurrencyManager} with the specified configuration.
     */
    public ConcurrencyManager build();
}
