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

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;

/**
 * {@link ContextManager} instances can be used to create {@link #newManagedExecutorBuilder()}
 * or {@link #newThreadContextBuilder()}. Each {@link ContextManager} instance has its own set
 * of {@link ThreadContextProvider} as defined during building with {@link ContextManager.Builder#build()}
 * or {@link ContextManagerProvider#getContextManager()}.
 *
 * @see ContextManagerProvider#getContextManager()
 * @see ContextManager.Builder
 */
public interface ContextManager {
    /**
     * Creates a new {@link org.eclipse.microprofile.context.ManagedExecutor.Builder ManagedExecutor.Builder} instance.
     *
     * @return a new {@link org.eclipse.microprofile.context.ManagedExecutor.Builder ManagedExecutor.Builder} instance.
     */
    ManagedExecutor.Builder newManagedExecutorBuilder();

    /**
     * Creates a new {@link org.eclipse.microprofile.context.ThreadContext.Builder ThreadContext.Builder} instance.
     *
     * @return a new {@link org.eclipse.microprofile.context.ThreadContext.Builder ThreadContext.Builder} instance.
     */
    ThreadContext.Builder newThreadContextBuilder();

    /**
     * Use this class to configure instances of {@link ContextManager}.
     */
    public interface Builder {
        
        /**
         * Use the specified {@link ThreadContextProvider} instances.
         * @param providers the {@link ThreadContextProvider} instances to use.
         * @return this builder
         */
        public Builder withThreadContextProviders(ThreadContextProvider... providers);

        /**
         * Load all discoverable {@link ContextManagerExtension} instances via the {@link ServiceLoader}
         * mechanism on the current thread-context {@link ClassLoader} (unless overridden by {@link #forClassLoader(ClassLoader)}).
         * 
         * @return this builder
         */
        public ContextManager.Builder addDiscoveredContextManagerExtensions();

        /**
         * Use the specified {@link ContextManagerExtension} instances.
         * @param extensions the {@link ContextManagerExtension} instances to use.
         * @return this builder
         */
        public Builder withContextManagerExtensions(ContextManagerExtension... extensions);

        /**
         * Load all discoverable {@link ThreadContextProvider} instances via the {@link ServiceLoader}
         * mechanism on the current thread-context {@link ClassLoader} (unless overridden by {@link #forClassLoader(ClassLoader)}).
         * 
         * @return this builder
         */
        public Builder addDiscoveredThreadContextProviders();

        /**
         * Use the given {@link ClassLoader} for {@link #addDiscoveredThreadContextProviders()} instead
         * of the current thread-context {@link ClassLoader}.
         * 
         * @param classLoader the {@link ClassLoader} to use for {@link #addDiscoveredThreadContextProviders()}
         * @return this builder
         */
        public Builder forClassLoader(ClassLoader classLoader);
        
        /**
         * <p>Creates a new {@link ContextManager} with the specified configuration.</p>
         * 
         * <p>Creating a {@link ContextManager} will load and invoke all related
         * {@link ContextManagerExtension} as described in its documentation.</p>
         * 
         * @return a new {@link ContextManager} with the specified configuration.
         */
        public ContextManager build();
    }

}
