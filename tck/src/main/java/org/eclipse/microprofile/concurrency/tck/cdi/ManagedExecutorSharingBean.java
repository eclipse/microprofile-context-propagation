/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.concurrency.tck.cdi;

import static org.testng.Assert.*;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;
import org.eclipse.microprofile.concurrent.ThreadContext;

@ApplicationScoped
public class ManagedExecutorSharingBean {
    @Inject
    @ManagedExecutorConfig(propagated = ThreadContext.CDI, cleared = ThreadContext.ALL_REMAINING)
    ManagedExecutor unnamedExec1;

    @Inject
    @ManagedExecutorConfig(propagated = ThreadContext.CDI, cleared = ThreadContext.ALL_REMAINING)
    ManagedExecutor unnamedExec2;

    @Inject
    @NamedInstance("A")
    @ManagedExecutorConfig(propagated = ThreadContext.CDI, cleared = ThreadContext.ALL_REMAINING)
    ManagedExecutor namedExecA1;

    @Inject
    @NamedInstance("A")
    ManagedExecutor namedExecA2;

    @Inject
    @NamedInstance("B")
    @ManagedExecutorConfig(propagated = ThreadContext.CDI, cleared = ThreadContext.ALL_REMAINING)
    ManagedExecutor namedExecB1;

    @Inject
    @NamedInstance("B")
    ManagedExecutor namedExecB2;

    /**
     * Verify the container creates a ManagedExecutor instance per unqualified injection point w/ config
     */
    public void testUnnamedConfigDifferent() {
        assertNotSame(unnamedExec1.toString(), unnamedExec2.toString(),
                "Default injection points with matching @ManagedExecutorConfig should get different instances");
    }

    /**
     * Verify injection points with matching @NamedInstance qualifiers share an ME instance
     */
    public void testNamedExecsSame() {
        assertEquals(namedExecA1.toString(), namedExecA2.toString(),
                "Two injection points with @NamedInstance(\"A\") should share the same ManagedExecutor");
        assertEquals(namedExecB1.toString(), namedExecB2.toString(),
                "Two injection points with @NamedInstance(\"B\") should share the same ManagedExecutor");
    }

    /**
     * Verify injection points with different @NamedInstance qualifiers do NOT share an ME instance
     */
    public void testDifferentNamedMEDifferent() {
        assertNotSame(namedExecA1.toString(), namedExecB1.toString(),
                "Two injection points with different @NamedInstance values should get separate ManagedExecutor instances");
    }
}
