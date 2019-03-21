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

/**
 * <p><code>ContextManagerExtension</code> instances receive
 * notification upon creation of each {@link ContextManager}.</p>
 *
 * <p>This serves as a convenient invocation point for enabling system wide
 * context propagator hooks.</p>
 *
 * <p>Implementations of <code>ContextManagerExtension</code>
 * and related classes are packaged within a third party JAR file, or they can
 * be supplied by the container or MicroProfile Context Propagation implementation.
 * <code>ContextManagerExtension</code>s are made discoverable
 * via the standard <code>ServiceLoader</code> mechanism. The JAR file that
 * packages it must include a file of the following name and location,</p>
 *
 * <code>META-INF/services/org.eclipse.microprofile.context.spi.ContextManagerExtension</code>
 *
 * <p>The content of the aforementioned file must be one or more lines, each
 * specifying the fully qualified name of a
 * <code>ContextManagerExtension</code> implementation that is
 * provided within the JAR file.</p>
 *
 * <p>Upon successful {@link ContextManager} creation, 
 * the MicroProfile Context Propagation implementation must subsequently query the container's
 * <code>ServiceLoader</code> for all
 * <code>ContextManagerExtension</code>s and call the
 * <code>setup</code> method on each instance.</p>
 */
public interface ContextManagerExtension {
    /**
     * This method is called after every <code>ContextManager</code>
     * instance has been successfully created. Implementations may use
     * the supplied manager to create and configure builders and build
     * instances of <code>ManagedExecutor</code> and
     * <code>ThreadContext</code>. 
     *
     * @param manager the <code>ContextManager</code> instance that was
     *        just created. 
     */
    void setup(ContextManager manager);
}