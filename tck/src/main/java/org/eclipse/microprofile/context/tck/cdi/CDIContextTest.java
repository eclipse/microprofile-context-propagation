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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.ITestResult;
import org.testng.annotations.Test;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

public class CDIContextTest extends Arquillian {

    static final int TIMEOUT_MIN = 2;

    @Inject
    Instance<Object> instance;

    @Inject
    BeanManager bm;

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
        WebArchive war = ShrinkWrap.create(WebArchive.class, CDIContextTest.class.getSimpleName() + ".war")
                .addClass(AbstractBean.class)
                .addClass(RequestScopedBean.class)
                .addClass(SessionScopedBean.class)
                .addClass(ConversationScopedBean.class)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
        System.out.println(war.toString(true));
        return war;
    }
    
    /**
     * Set some state on Request scoped bean and verify
     * the state is propagated to the thread where the other task runs.
     *
     * If the CDI context in question is not active, the test is deemed successful as there is no propagation to be done.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDIMECtxPropagatesRequestScopedBean() throws Exception {
        // check if given context is active, if it isn't test ends successfully
        try {
            bm.getContext(RequestScoped.class);
        } catch (ContextNotActiveException e) {
            return;
        }

        ManagedExecutor propagateCDI = ManagedExecutor.builder().propagated(ThreadContext.CDI)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        Instance<RequestScopedBean> selectedInstance = instance.select(RequestScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        try {
            checkCDIPropagation(true, "testCDI_ME_Ctx_Propagate-REQUEST", propagateCDI,
                    selectedInstance.get());
        } 
        finally {
            propagateCDI.shutdown();
        }
    }
    /**
     * Set some state on Session scoped bean and verify
     * the state is propagated to the thread where the other task runs.
     *
     * If the CDI context in question is not active, the test is deemed successful as there is no propagation to be done.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDIMECtxPropagatesSessionScopedBean() throws Exception {
        // check if given context is active, if it isn't test ends successfully
        try {
            bm.getContext(SessionScoped.class);
        } catch (ContextNotActiveException e) {
            return;
        }

        ManagedExecutor propagateCDI = ManagedExecutor.builder().propagated(ThreadContext.CDI)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        Instance<SessionScopedBean> selectedInstance = instance.select(SessionScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        try {
            checkCDIPropagation(true, "testCDI_ME_Ctx_Propagate-SESSION", propagateCDI,
                    selectedInstance.get());
        }
        finally {
            propagateCDI.shutdown();
        }
    }
    /**
     * Set some state on Conversation scoped beans and verify
     * the state is propagated to the thread where the other task runs.
     *
     * If the CDI context in question is not active, the test is deemed successful as there is no propagation to be done.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDIMECtxPropagatesConversationScopedBean() throws Exception {
        // check if given context is active, if it isn't test ends successfully
        try {
            bm.getContext(ConversationScoped.class);
        } catch (ContextNotActiveException e) {
            return;
        }

        ManagedExecutor propagateCDI = ManagedExecutor.builder().propagated(ThreadContext.CDI)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        Instance<ConversationScopedBean> selectedInstance = instance.select(ConversationScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        try {
            checkCDIPropagation(true, "testCDI_ME_Ctx_Propagate-CONVERSATION", propagateCDI,
                    selectedInstance.get());
        }
        finally {
            propagateCDI.shutdown();
        }
    }

    /**
     * Set some state on Request scoped bean and verify
     * the state is cleared on the thread where the other task runs.
     *
     * If the CDI context in question is not active, the test is deemed successful as there is no propagation to be done.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDIMECtxClearsRequestScopedBean() throws Exception {
        // check if given context is active, if it isn't test ends successfully
        try {
            bm.getContext(RequestScoped.class);
        } catch (ContextNotActiveException e) {
            return;
        }

        ManagedExecutor propagatedNone = ManagedExecutor.builder()
                .propagated() // none
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        Instance<RequestScopedBean> selectedInstance = instance.select(RequestScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        try {
            checkCDIPropagation(false, "testCDI_ME_Ctx_Clear-REQUEST", propagatedNone,
                    selectedInstance.get());
        } 
        finally {
            propagatedNone.shutdown();
        }
    }

    /**
     * Set some state on Session scoped bean and verify
     * the state is cleared on the thread where the other task runs.
     *
     * If the CDI context in question is not active, the test is deemed successful as there is no propagation to be done.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDIMECtxClearsSessionScopedBeans() throws Exception {
        // check if given context is active, if it isn't test ends successfully
        try {
            bm.getContext(SessionScoped.class);
        } catch (ContextNotActiveException e) {
            return;
        }

        ManagedExecutor propagatedNone = ManagedExecutor.builder()
                .propagated() // none
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        Instance<SessionScopedBean> selectedInstance = instance.select(SessionScopedBean.class);
        assertTrue(selectedInstance.isResolvable());

        try {
            checkCDIPropagation(false, "testCDI_ME_Ctx_Clear-SESSION", propagatedNone,
                    selectedInstance.get());
        }
        finally {
            propagatedNone.shutdown();
        }
    }

    /**
     * Set some state on Conversation scoped bean and verify
     * the state is cleared on the thread where the other task runs.
     *
     * If the CDI context in question is not active, the test is deemed successful as there is no propagation to be done.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDIMECtxClearsConversationScopedBeans() throws Exception {
        // check if given context is active, if it isn't test ends successfully
        try {
            bm.getContext(ConversationScoped.class);
        } catch (ContextNotActiveException e) {
            return;
        }

        ManagedExecutor propagatedNone = ManagedExecutor.builder()
                .propagated() // none
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        Instance<ConversationScopedBean> selectedInstance = instance.select(ConversationScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        try {
            checkCDIPropagation(false, "testCDI_ME_Ctx_Clear-CONVERSATION", propagatedNone,
                    selectedInstance.get());
        }
        finally {
            propagatedNone.shutdown();
        }
    }

    private void checkCDIPropagation(boolean expectPropagate, String stateToPropagate, ManagedExecutor me, AbstractBean bean) throws Exception {
        bean.setState(stateToPropagate);
        CompletableFuture<String> cf = me.supplyAsync(() -> {
            String state = bean.getState();
            return state;
        });
        assertEquals(cf.get(TIMEOUT_MIN, TimeUnit.MINUTES), expectPropagate ? stateToPropagate : AbstractBean.UNINITIALIZED);
    }

    /**
     * Set some state on a request scoped bean, then verify a contextualized callable
     * has the state propagated to it when ran on the same thread.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDITCCtxPropagate() throws Exception {
        ThreadContext defaultTC = ThreadContext.builder()
                                               .propagated(ThreadContext.CDI)
                                               .cleared(ThreadContext.ALL_REMAINING)
                                               .unchanged()
                                               .build();

        Instance<RequestScopedBean> selectedInstance = instance.select(RequestScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        RequestScopedBean requestBean = selectedInstance.get();
        requestBean.setState("testCDIContextPropagate-STATE2");
        Callable<String> getState = defaultTC.contextualCallable(() -> {
            String state = requestBean.getState();
            return state;
        });
        assertEquals(getState.call(), "testCDIContextPropagate-STATE2");
    }

    /**
     * Set some state on a request scoped bean, then verify a contextualized callable
     * has the state cleared from it when ran on the same thread.
     *
     * @throws Exception indicates test failure
     */
    @Test
    public void testCDITCCtxClear() throws Exception {
        ThreadContext clearAllCtx = ThreadContext.builder()
                        .propagated() // propagate nothing
                        .cleared(ThreadContext.ALL_REMAINING)
                        .unchanged()
                        .build();

        Instance<RequestScopedBean> selectedInstance = instance.select(RequestScopedBean.class);
        assertTrue(selectedInstance.isResolvable());
        RequestScopedBean requestBean = selectedInstance.get();
        requestBean.setState("testCDIThreadCtxClear-STATE1");
        Callable<String> getState = clearAllCtx.contextualCallable(() -> {
            String state = requestBean.getState();
            return state;
        });
        assertEquals(getState.call(), "UNINITIALIZED");
    }
}
