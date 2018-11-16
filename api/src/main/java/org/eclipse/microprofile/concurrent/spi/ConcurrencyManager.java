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

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ThreadContext;

/**
 * {@link ConcurrencyManager} instances can be used to create {@link #newManagedExecutorBuilder()}
 * or {@link #newThreadContextBuilder()}. Each {@link ConcurrencyManager} instance has its own set
 * of {@link ThreadContextProvider} as defined during building with {@link ConcurrencyManagerBuilder#build()}
 * or {@link ConcurrencyProvider#getConcurrencyManager()}.
 *
 * @see ConcurrencyProvider#getConcurrencyManager()
 * @see ConcurrencyManagerBuilder
 */
public interface ConcurrencyManager {
    /**
     * Creates a new {@link org.eclipse.microprofile.concurrent.ManagedExecutor.Builder ManagedExecutor.Builder} instance.
     *
     * @return a new {@link org.eclipse.microprofile.concurrent.ManagedExecutor.Builder ManagedExecutor.Builder} instance.
     */
    ManagedExecutor.Builder newManagedExecutorBuilder();

    /**
     * Creates a new {@link org.eclipse.microprofile.concurrent.ThreadContext.Builder ThreadContext.Builder} instance.
     *
     * @return a new {@link org.eclipse.microprofile.concurrent.ThreadContext.Builder ThreadContext.Builder} instance.
     */
    ThreadContext.Builder newThreadContextBuilder();

}
