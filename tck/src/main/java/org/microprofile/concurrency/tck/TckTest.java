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
package org.microprofile.concurrency.tck;

import javax.inject.Inject;

import org.eclipse.microprofile.concurrent.ThreadContext;
import org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public class TckTest {

    @Deployment
    public static WebArchive createDeployment() {
        JavaArchive testJar = ShrinkWrap.create(JavaArchive.class)
                .addClass(TckTest.class)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
        WebArchive war = ShrinkWrap.create(WebArchive.class, "tckTest.war")
                .addAsLibrary(testJar);
        return war;
    }

    @Test
    public void providerSet() {
        ConcurrencyProvider provider = ConcurrencyProvider.instance();
        Assert.assertNotNull("Provider is set", provider);
    }

    @Inject
    ThreadContext injectedContext;

    @Test
    public void injectionWorks() {
        Assert.assertNotNull("Injected context works", injectedContext);
    }
}
