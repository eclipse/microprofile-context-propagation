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

import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Qualifier;

/**
 * <p>Qualifies a CDI injection point for a {@link ManagedExecutor} or {@link ThreadContext} with a unique name.</p>
 *
 * <p>This annotation can be used in combination with the {@link ManagedExecutorConfig} or {@link ThreadContextConfig}
 * annotation to define a new instance. For example,</p>
 *
 * <pre><code> &commat;Inject &commat;NamedInstance("myExecutor") &commat;ManagedExecutorConfig(maxAsync=10)
 * ManagedExecutor myExecutor;
 *
 * &commat;Inject &commat;NamedInstance("myContext") &commat;ThreadContextConfig(propagated = { ThreadContext.SECURITY, ThreadContext.CDI })
 * ThreadContext myThreadContext;
 * </code></pre>
 *
 * <p>This annotation can be used on its own to qualify an injection point with the name of
 * an existing instance. For example, referencing a name from the previous example,</p>
 *
 * <pre><code> &commat;Inject &commat;NamedInstance("myExecutor")
 * ManagedExecutor exec1;
 *
 * &commat;Inject &commat;NamedInstance("myContext")
 * ThreadContext myContextPropagator;
 * </code></pre>
 *
 * <p>Alternatively, an application can use this annotation as a normal CDI qualifier,
 * defining its own scope, producer, and disposer. For example,</p>
 *
 * <pre><code> &commat;Produces &commat;ApplicationScoped &commat;NamedInstance("exec2")
 * ManagedExecutor exec2 = ManagedExecutor.builder().maxAsync(5).build();
 *
 * public void shutdown(&commat;Disposes &commat;NamedInstance("exec2") ManagedExecutor executor) {
 *     executor.shutdown();
 * }
 *
 * int doSomething(&commat;Inject &commat;NamedInstance("exec2") ManagedExecutor executor) {
 *     ...
 * }
 * </code></pre>
 */
@Qualifier
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, TYPE })
public @interface NamedInstance {
    /**
     * Unique name that qualifies a {@link ManagedExecutor} or {@link ThreadContext}.
     */
    String value();
    
    /**
     * Supports inline instantiation of the {@link NamedInstance} qualifier.
     *
     */
    public final class Literal extends AnnotationLiteral<NamedInstance> implements NamedInstance {

        private static final long serialVersionUID = 1L;
        private final String value;

        public static Literal of(String value) {
            return new Literal(value);
        }

        public String value() {
            return value;
        }

        private Literal(String value) {
            this.value = value;
        }
    }
}