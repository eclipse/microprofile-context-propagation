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

import java.util.Arrays;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.concurrent.ManagedExecutor;
import org.eclipse.microprofile.concurrent.ManagedExecutorConfig;
import org.eclipse.microprofile.concurrent.NamedInstance;

@ApplicationScoped
public class ManagedExecutorSharingBean {

    @Inject
    ManagedExecutor defaultME;

    @Inject
    ManagedExecutor defaultME2;

    @Inject
    @ManagedExecutorConfig
    ManagedExecutor defaultAnno1;

    @Inject
    @ManagedExecutorConfig
    ManagedExecutor defaultAnno2;

    @Inject
    @NamedInstance("A")
    @ManagedExecutorConfig
    ManagedExecutor namedExecA1;

    @Inject
    @NamedInstance("A")
    ManagedExecutor namedExecA2;

    @Inject
    @NamedInstance("B")
    @ManagedExecutorConfig
    ManagedExecutor namedExecB1;

    @Inject
    @NamedInstance("B")
    ManagedExecutor namedExecB2;

    /**
     * Verify the container creates a ManagedExecutor instance per unqualified ManagedExecutor injection point
     */
    public void testDefaultMEsDifferent() {
        assertNotSame(defaultME.toString(), defaultME2.toString(),
                "Default injection points \"@Inject ManagedExecutor exec;\" should get different instances");
    }

    /**
     * Verify the container creates a ManagedExecutor instance per unqualified injection point w/ config
     */
    public void testUnnamedConfigDifferent() {
        assertNotSame(defaultAnno1.toString(), defaultAnno2.toString(),
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

    public void testAllDefaultInjectionUnique() {
        assertUnique(defaultME.toString(), defaultME2.toString(), defaultAnno1.toString(), defaultAnno2.toString());
    }

    private void assertUnique(String... executors) {
        for (int i = 0; i < executors.length; i++) {
            for (int j = i + 1; j < executors.length; j++) {
                assertNotSame(executors[i], executors[j], "Expected all instances to be unique, but index " + i
                        + " and " + j + " were the same: " + Arrays.toString(executors));
            }
        }
    }

}
