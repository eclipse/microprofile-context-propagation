/*
 * Copyright (c) 2019,2021 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.context.tck.cdi;

import java.lang.reflect.Method;

import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.tck.contexts.buffer.spi.BufferContextProvider;
import org.eclipse.microprofile.context.tck.contexts.label.spi.LabelContextProvider;
import org.eclipse.microprofile.context.tck.contexts.priority.spi.ThreadPriorityContextProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import jakarta.inject.Inject;

public class BasicCDITest extends Arquillian {

    // Delegate all CDI tests off to a proper CDI bean
    // Injection in this class is mocked by the Arquillian test enricher
    @Inject
    CDIBean bean;

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
        // build a JAR that provides three fake context types: 'Buffer', 'Label', and 'ThreadPriority'
        JavaArchive fakeContextProviders = ShrinkWrap.create(JavaArchive.class, "fakeContextTypes.jar")
                .addPackages(true, "org.eclipse.microprofile.context.tck.contexts.buffer")
                .addPackages(true, "org.eclipse.microprofile.context.tck.contexts.label")
                .addPackage("org.eclipse.microprofile.context.tck.contexts.priority.spi")
                .addAsServiceProvider(ThreadContextProvider.class,
                        BufferContextProvider.class, LabelContextProvider.class, ThreadPriorityContextProvider.class);

        return ShrinkWrap.create(WebArchive.class, BasicCDITest.class.getSimpleName() + ".war")
                .addClass(CDIBean.class)
                .addClass(CdiBeanProducer.class)
                .addClass(BasicCDITest.class)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsLibraries(fakeContextProviders);
    }

    @Test
    public void testVerifyInjection() {
        bean.testVerifyInjection();
    }

    @Test
    public void testBasicExecutorUsable() throws Exception {
        bean.testBasicExecutorUsable();
    }

    @Test
    public void applicationDefinesProducerOfThreadContext() {
        bean.testAppDefinedProducerOfThreadContext();
    }

    @Test
    public void applicationDefinesProducerUsingInjectedThreadContext() {
        bean.testAppDefinedProducerUsingInjectedThreadContext();
    }
}
