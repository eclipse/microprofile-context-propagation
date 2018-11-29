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
package org.eclipse.microprofile.concurrent;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

/**
 * <p>Qualifies a CDI injection point for a {@link ThreadContext} with a unique name.</p>
 *
 * <p>This annotation can be used in combination with the {@link ThreadContextConfig}
 * annotation to define a new instance. For example,</p>
 *
 * <pre><code> &commat;Inject &commat;NamedContext("myContext") &commat;ThreadContextConfig({ ThreadContext.SECURITY, ThreadContext.CDI })
 * ThreadContext myThreadContext;
 * </code></pre>
 *
 * <p>This annotation can be used on its own to qualify an injection point with the name of
 * an existing instance. For example, referencing the thread context name from the previous example,</p>
 *
 * <pre><code> &commat;Inject &commat;NamedContext("myContext")
 * ThreadContext myContextPropagator;
 * </code></pre>
 *
 * <p>Alternatively, an application can use this annotation as a normal CDI qualifier,
 * defining its own scope and producer. For example,</p>
 *
 * <pre><code> &commat;Produces &commat;ApplicationScoped &commat;NamedContext("secContext")
 * ThreadContext secContextOnly = ThreadContext.builder().propagated(ThreadContext.SECURITY).build();
 *
 * void doSomething(&commat;Inject &commat;NamedContext("secContext") ThreadContext secContextPropagator) {
 *     ...
 * }
 * </code></pre>
 */
@Qualifier
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, TYPE })
public @interface NamedContext {
    /**
     * Unique name that qualifies a {@link ThreadContext}.
     */
    String value();
}