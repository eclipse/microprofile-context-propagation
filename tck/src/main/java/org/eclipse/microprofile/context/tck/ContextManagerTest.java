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
package org.eclipse.microprofile.context.tck;

import java.lang.reflect.Method;

import org.eclipse.microprofile.context.spi.ContextManager;
import org.eclipse.microprofile.context.spi.ContextManager.Builder;
import org.eclipse.microprofile.context.spi.ContextManagerProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ContextManagerTest extends Arquillian {

    @AfterMethod
    public void afterMethod(Method m, ITestResult result) {
        System.out.println("<<< END " + m.getClass().getSimpleName() + '.' + m.getName()
                + (result.isSuccess() ? " SUCCESS" : " FAILED"));
        Throwable failure = result.getThrowable();
        if (failure != null) {
            failure.printStackTrace(System.out);
        }
    }

    @BeforeMethod
    public void beforeMethod(Method m) {
        System.out.println(">>> BEGIN " + m.getClass().getSimpleName() + '.' + m.getName());
    }

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, ContextManagerTest.class.getSimpleName() + ".war")
                .addClass(ContextManagerTest.class);
    }

    /**
     * Verify obtaining a ContextManager builder from the ContextManagerProvider. Then validate building, registering,
     * and releasing a custom ContextManager. If a ContextManager builder is not supported then this test will no-op.
     */
    @Test
    public void builderForContextManagerIsProvided() {
        ContextManagerProvider provider = ContextManagerProvider.instance();
        ClassLoader classLoader = ContextManagerTest.class.getClassLoader();
        Builder contextManagerBuilder = null;

        try {
            // obtain the ContextManagerBuilder
            contextManagerBuilder = provider.getContextManagerBuilder();
            Assert.assertNotNull(contextManagerBuilder,
                    "MicroProfile Context Propagation implementation does not provide a ContextManager builder.");
        } catch (UnsupportedOperationException ex1) {
            // ContextManagerProvider.getContextManagerBuilder() support is optional.
            System.out.println("ContextManagerProvider.getContextManagerBuilder is not supported.");

            // Verify ContextManagerProvider.registerContextManager() is also unsupported.
            try {
                provider.registerContextManager(provider.getContextManager(), classLoader);
                Assert.fail("ContextManagerProvider.registerContextManager should not be supported, "
                        + "if ContextManagerProvider.getContextManagerBuilder is not supported.");
            } catch (UnsupportedOperationException ex2) {
                System.out.println("ContextManagerProvider.registerContextManager is not supported.");
            }

            // Verify ContextManagerProvider.releaseContextManager() is also unsupported.
            try {
                provider.releaseContextManager(provider.getContextManager());
                Assert.fail("ContextManagerProvider.releaseContextManager should not be supported, "
                        + "if ContextManagerProvider.getContextManagerBuilder is not supported.");
            } catch (UnsupportedOperationException ex3) {
                System.out.println("ContextManagerProvider.releaseContextManager is not supported.");
            }

            // Unsupported path of test has passed.
            return;
        }

        // build and register a ContextManager
        ContextManager builtManager = contextManagerBuilder.build();
        provider.registerContextManager(builtManager, classLoader);
        ContextManager registeredManager = provider.getContextManager(classLoader);
        Assert.assertEquals(builtManager, registeredManager,
                "ContextManagerProvider.getContextManager(classLoader) did not return the same manager that was registered.");

        // release the ContextManager
        provider.releaseContextManager(registeredManager);
        Assert.assertNotEquals(builtManager, provider.getContextManager(classLoader),
                "ContextManager was not released from the ContextManagerProvider.");
    }
}
