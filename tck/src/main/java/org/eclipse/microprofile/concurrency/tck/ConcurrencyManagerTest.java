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
package org.eclipse.microprofile.concurrency.tck;

import java.lang.reflect.Method;

import org.eclipse.microprofile.concurrent.spi.ConcurrencyManager;
import org.eclipse.microprofile.concurrent.spi.ConcurrencyManager.Builder;
import org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ConcurrencyManagerTest extends Arquillian {

    @AfterMethod
    public void afterMethod(Method m, ITestResult result) {
        System.out.println("<<< END " + m.getClass().getSimpleName() + '.' + m.getName() + (result.isSuccess() ? " SUCCESS" : " FAILED"));
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
        return ShrinkWrap.create(WebArchive.class, ConcurrencyManagerTest.class.getSimpleName() + ".war")
                .addClass(ConcurrencyManagerTest.class);
    }

    /**
     * Verify obtaining a ConcurrencyManager builder from the ConcurrencyProvider. Then validate building,
     * registering, and releasing a custom ConcurrencyManager. If a ConcurrencyManager builder is not supported
     * then this test will no-op.
     */
    @Test
    public void builderForConcurrencyManagerIsProvided() {
        try {
            ConcurrencyProvider provider = ConcurrencyProvider.instance();
            ClassLoader classLoader = ConcurrencyManagerTest.class.getClassLoader();
            
            //obtain the ConcurrencyManagerBuilder
            Builder concurrencyManagerBuilder = provider.getConcurrencyManagerBuilder();
            Assert.assertNotNull(concurrencyManagerBuilder,
                    "MicroProfile Concurrency implementation does not provide a ConcurrencyManager builder.");
            
            //build and register a ConcurrencyManager
            ConcurrencyManager builtManager = concurrencyManagerBuilder.build();
            provider.registerConcurrencyManager(builtManager, classLoader);
            ConcurrencyManager registeredManager = provider.getConcurrencyManager(classLoader);
            Assert.assertEquals(builtManager, registeredManager,
                    "ConcurrencyManager.getConcurrencyManager(classLoader) did not return the same manager that was registered.");
            
            //release the ConcurrencyManager
            provider.releaseConcurrencyManager(registeredManager);
            Assert.assertNotEquals(builtManager, provider.getConcurrencyManager(classLoader),
                    "ConcurrencyManager was not released from the ConcurencyProvider.");
            
        } 
        catch (UnsupportedOperationException ex) {
            // Support is optional, allow test to pass.
            System.out.println("Skipping test builderForConcurrencyManagerIsProvided. "
                    + "ConcurrencyProvider.getConcurrencyManagerBuilder is not supported.");
        }        
    }
}
