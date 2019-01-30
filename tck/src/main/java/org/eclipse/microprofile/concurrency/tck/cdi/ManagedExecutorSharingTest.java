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

import java.lang.reflect.Method;

import javax.inject.Inject;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.Test;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

/**
 * Tests sharing rules of ManagedExecutors injected via CDI
 */
public class ManagedExecutorSharingTest extends Arquillian {

    // Delegate all CDI tests off to a proper CDI bean
    // Injection in this class is mocked by the Arquillian test enricher
    @Inject
    ManagedExecutorSharingBean bean;

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
        return ShrinkWrap.create(WebArchive.class, ManagedExecutorSharingTest.class.getSimpleName() + ".war")
                .addClass(ManagedExecutorSharingBean.class)
                .addClass(ManagedExecutorSharingTest.class)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
    }
    
    @Test
    public void testDefaultMEsDifferent() {
        Assert.assertNotNull(bean, "The CDIBean was not injected into this test");
        bean.testDefaultMEsDifferent();
    }

    @Test
    public void testUnnamedConfigDifferent() {
        bean.testUnnamedConfigDifferent();
    }

    @Test
    public void testAllDefaultInjectionUnique() {
        bean.testAllDefaultInjectionUnique();
    }

    @Test
    public void testNamedExecsSame() {
        bean.testNamedExecsSame();
    }
    
    @Test
    public void testDifferentNamedMEDifferent() {
        bean.testDifferentNamedMEDifferent();
    }

}
