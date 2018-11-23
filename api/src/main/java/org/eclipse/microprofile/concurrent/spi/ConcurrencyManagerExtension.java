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
 * <p><code>ConcurrencyManagerExtension</code> instances receive
 * notification upon creation of each {@link ConcurrencyManager}.</p>
 *
 * <p>This serves as a convenient invocation point for enabling system wide
 * context propagator hooks.</p>
 *
 * <p>Implementations of <code>ConcurrencyManagerExtension</code>
 * and related classes are packaged within a third party JAR file, or they can
 * be supplied by the container or MicroProfile Concurrency implementation.
 * <code>ConcurrencyManagerExtension</code>s are made discoverable
 * via the standard <code>ServiceLoader</code> mechanism. The JAR file that
 * packages it must include a file of the following name and location,</p>
 *
 * <code>META-INF/services/org.eclipse.microprofile.concurrent.spi.ConcurrencyManagerExtension</code>
 *
 * <p>The content of the aforementioned file must be one or more lines, each
 * specifying the fully qualified name of a
 * <code>ConcurrencyManagerExtension</code> implementation that is
 * provided within the JAR file.</p>
 *
 * <p>Upon successful {@link ConcurrencyManager} creation, 
 * the MicroProfile Concurrency implementation must subsequently query the container's
 * <code>ServiceLoader</code> for all
 * <code>ConcurrencyManagerExtension</code>s and call the
 * <code>setup</code> method on each instance.</p>
 */
public interface ConcurrencyManagerExtension {
    /**
     * This method is called after every <code>ConcurrencyManager</code>
     * instance has been successfully created. Implementations may use
     * the supplied manager to create and configure builders and build
     * instances of <code>ManagedExecutor</code> and
     * <code>ThreadContext</code>. 
     *
     * @param manager the <code>ConcurrencyManager</code> instance that was
     *        just created. 
     */
    void setup(ConcurrencyManager manager);
}