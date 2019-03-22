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
package org.eclipse.microprofile.context.tck.cdi;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.transaction.NotSupportedException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;
import javax.transaction.UserTransaction;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/*
 * Tests for propagating transaction contexts
 */
public class JTACDITest extends Arquillian {
    public static final int UNSUPPORTED = -1000;

    @Inject
    private TransactionalService transactionalService;

    @AfterMethod
    public void afterMethod(Method m, ITestResult result) {
        System.out.printf("<<< END %s.%s%n", m.getName(), (result.isSuccess() ? " SUCCESS" : " FAILED"));
        Throwable failure = result.getThrowable();
        if (failure != null) {
            failure.printStackTrace(System.out);
        }
    }

    @BeforeMethod
    public void beforeMethod(Method m) {
        System.out.printf(">>> BEGIN %s.%s%n", m.getClass().getSimpleName(), m.getName());
    }

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, JTACDITest.class.getSimpleName() + ".war")
                .addClasses(TransactionalBean.class, TransactionalBeanImpl.class, TransactionalService.class)
                .addClass(JTACDITest.class);
    }

    /*
     * use a CDI injected transaction manager to:
     * - start a transaction and then,
     * - run single asynchronous stage and then
     * - verify that a transaction scoped bean is updated correctly and finally
     * - finish the transaction
     */
    @Test
    public void testTransaction() throws Exception {
        // create an executor that propagates the transaction context
        ManagedExecutor executor = createExecutor("testTransaction");
        UserTransaction ut = getUserTransaction("testTransaction");

        if (executor == null || ut == null) {
            return; // the implementation does not support transaction propagation
        }

        try {
            // enter transaction scope by starting a transaction
            ut.begin();
            int initialValue = transactionalService.getValue(); // transaction scoped beans will be available
            // call a transactional bean on another thread to validate that the transaction context propagates
            CompletableFuture<Void> stage;
            try {
                stage = executor.runAsync(() -> transactionalService.mandatory());
            }
            catch (IllegalStateException x) {
                System.out.println("Propagation of active transactions is not supported. Skipping test.");
                return;
            }
            try {
                stage.join();
            }
            catch (CompletionException x) {
                if (x.getCause() instanceof IllegalStateException) {
                    System.out.println("Propagation of active transactions to multiple threads in parallel is not supported. Skipping test.");
                    return;
                }
                else {
                    throw x;
                }
            }

            // we are still in transaction context so transaction scoped beans will still be available
            Assert.assertEquals(initialValue + 1, transactionalService.getValue());
        }
        finally {
            // Must end the transaction in the same thread it was started from.
            // If it is ended by an executor thread then the transaction context provider
            // will re-associate the terminated transaction with the initiating thread when
            // the executor fiishes.
            try {
                ut.rollback();

                try {
                    // transaction scoped beans should no longer be available since the
                    // transaction will have been disassociated
                    transactionalService.getValue();
                    Assert.fail("TransactionScoped bean should only be available from transaction scope");
                }
                catch (Exception ignore) {
                    // expected since we are no longer in the scope of a transactions
                }
            }
            finally {
                verifyNoTransaction();
            }
        }
    }

    /*
     * same as testTransaction but using @Transactional for transaction demarcation
     */
    @Test
    public void testAsyncTransaction() {
        // create an executor that propagates the transaction context
        ManagedExecutor executor = createExecutor("testAsyncTransaction");

        if (executor == null) {
            return; // the implementation does not support transaction propagation
        }

        try {
            // delegate this test to transactionalService which manages transactional
            // boundaries using the @Transactional annotation
            int result = transactionalService.testAsync(executor);
            if (result != UNSUPPORTED) {
                Assert.assertEquals(1, result,
                        "testAsyncTransaction failed%n");
            }
            verifyNoTransaction();
        }
        finally {
            executor.shutdownNow();
        }
    }

    /*
     * run asynchronous stages, one after the other, where each stage tests
     * one of the Transactional.TxType element attributes
     * (MANDATORY, NEVER, NOT_SUPPORTED, REQUIRED, REQUIRES_NEW, SUPPORTS).
     * Validate that a transaction scoped bean is updated in accordance
     * with the semantics of this element.
     */
    @Test
    public void testTransactionPropagation() throws Exception {
        // create an executor that propagates the transaction context
        ManagedExecutor executor = createExecutor("testTransactionPropagation");
        UserTransaction ut = getUserTransaction("testTransactionPropagation");

        if (executor == null || ut == null) {
            return;
        }

        try {
            try {
                ut.begin();
                int currentValue = transactionalService.getValue(); // the bean should be in scope

                CompletableFuture<Void> stage0;
                try {
                    // run various transactional updates on the executor
                    stage0 = executor.runAsync(() -> {
                        transactionalService.required(); // invoke a method that requires a transaction
                        // the service call should have updated the bean in this transaction scope
                        Assert.assertEquals(currentValue + 1, transactionalService.getValue());
                    });
                }
                catch (IllegalStateException x) {
                    System.out.println("Propagation of active transactions is not supported. Skipping test.");
                    return;
                }
                CompletableFuture<Void> stage1= stage0.thenRunAsync(() -> {
                    transactionalService.requiresNew();
                    // the service call should have updated a different bean in a different transaction scope
                    Assert.assertEquals(currentValue + 1, transactionalService.getValue());
                }).thenRunAsync(() -> {
                    // the service call should have updated the bean in this transaction scope
                    transactionalService.supports();
                    Assert.assertEquals(currentValue + 2, transactionalService.getValue());
                }).thenRunAsync(() -> {
                    // updating a transaction scoped bean outside of a transacction should fail
                    if (callServiceExpectFailure(Transactional.TxType.NEVER.name(),
                            TransactionalService::never, transactionalService)) {
                        // true means the feature is supported
                        Assert.assertEquals(currentValue + 2, transactionalService.getValue());
                    }
                }).thenRunAsync(() -> {
                    // updating a transaction scoped bean outside of a transacction should fail
                    if (callServiceExpectFailure(Transactional.TxType.NOT_SUPPORTED.name(),
                            TransactionalService::notSupported, transactionalService)) {
                        // true means the feature is supported
                        Assert.assertEquals(currentValue + 2, transactionalService.getValue());
                    }
                }).thenRunAsync(() -> {
                    transactionalService.mandatory();
                    // the service call should have updated the bean in this transaction scope
                    Assert.assertEquals(currentValue + 3, transactionalService.getValue());
                });

                try {
                    stage1.join();
                }
                catch (CompletionException x) {
                    if (x.getCause() instanceof IllegalStateException) {
                        System.out.println("Propagation of active transactions to multiple threads in parallel is not supported. Skipping test.");
                        return;
                    }
                    else {
                        throw x;
                    }
                }
                Assert.assertEquals(currentValue + 3, transactionalService.getValue());
            }
            finally {
                ut.rollback();
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    /*
     * Start two concurrent asynchronous stages and verify that
     * a transaction scoped bean is updated twice.
     */
    @Test
    @Ignore
    public void testConcurrentTransactionPropagation() {
        // create an executor that propagates the transaction context
        ManagedExecutor executor = createExecutor("testConcurrentTransactionPropagation");
        UserTransaction ut = getUserTransaction("testConcurrentTransactionPropagation");

        if (executor == null || ut == null) {
            return; // the implementation does not support transaction propagation
        }

        try {
            int result = transactionalService.testConcurrentTransactionPropagation(executor);
            if (result != UNSUPPORTED) {
                Assert.assertEquals(2, result, "testTransactionPropagation failed%n");
            }
            verifyNoTransaction();
        }
        finally {
            executor.shutdownNow();
        }
    }

    /*
     *  run under the transaction that is activate on the thread at the time when a task runs
     */
    @Test
    public void testRunWithTxnOfExecutingThread() throws SystemException, NotSupportedException {
        ThreadContext threadContext = ThreadContext.builder()
                .propagated()
                .unchanged(ThreadContext.TRANSACTION)
                .cleared(ThreadContext.ALL_REMAINING)
                .build();

        UserTransaction ut = getUserTransaction("testRunWithTxnOfExecutingThread");

        if (threadContext == null || ut == null) {
            return; // the implementation does not support transaction propagation
        }

        Callable<Boolean> isInTransaction =
                threadContext.contextualCallable(() -> ut.getStatus() == Status.STATUS_ACTIVE);

        ut.begin();

        try {
            Assert.assertTrue(isInTransaction.call());
        }
        catch (Exception e) {
            Assert.fail("testRunWithTxnOfExecutingThread: a transaction should have been active");
        }
        finally {
            ut.rollback();
        }
    }

    @Test
    public void testTransactionWithUT() throws Exception {
        // create an executor that propagates the transaction context
        ManagedExecutor executor = createExecutor("testTransactionWithUT");
        UserTransaction ut = getUserTransaction("testConcurrentTransactionPropagation");

        if (executor == null || ut == null) {
            return; // the implementation does not support transaction propagation
        }

        TransactionalService service = CDI.current().select(TransactionalService.class).get();

        ut.begin();
        Assert.assertEquals(0, service.getValue());

        CompletableFuture<Void> stage;
        try {
            stage = executor.runAsync(service::mandatory);
        }
        catch (IllegalStateException x) {
            System.out.println("Propagation of active transactions is not supported. Skipping test.");
            return;
        }

        try {
            stage.join();
        }
        catch (CompletionException x) {
            if (x.getCause() instanceof IllegalStateException) {
                System.out.println("Propagation of active transactions to multiple threads in parallel is not supported. Skipping test.");
                return;
            }
            else {
                throw x;
            }
        }

        try {
            ut.rollback();
            Assert.assertEquals(ut.getStatus(), Status.STATUS_NO_TRANSACTION,
                    "transaction still active");
        }
        catch (SystemException e) {
            e.printStackTrace();
        }
    }


    @FunctionalInterface
    interface TransactionalServiceCall<TransactionalService> {
        void apply(TransactionalService ts);
    }

    // utility mehtod to avoid having to duplicate code
    private boolean callServiceExpectFailure(String txType,
                                          TransactionalServiceCall<TransactionalService> op,
                                          TransactionalService ts) {
        try {
            ThreadContext.builder()
                    .propagated()
                    .cleared(ThreadContext.TRANSACTION)
                    .unchanged(ThreadContext.ALL_REMAINING)
                    .build().contextualRunnable(() -> callServiceWithoutContextExpectFailure(op, ts)).run();

            return true;
        }
        catch (IllegalStateException e) {
            System.out.printf("Skipping testTransactionPropagation for %s. Transaction context propagation is not supported.%n",
                    txType);
            return false;
        }
    }

    private void callServiceWithoutContextExpectFailure(TransactionalServiceCall<TransactionalService> op,
            TransactionalService ts) {
        try {

            op.apply(ts);
            Assert.fail("TransactionScoped bean should only be available from transaction scope");
        }
        catch (Exception ignore) {
            // expected
        }
    }

    // verify that there the is no transaction associated with the calling thread
    private void verifyNoTransaction() {
        try {
            TransactionManager transactionManager = CDI.current().select(TransactionManager.class).get();

            try {
                if (transactionManager.getTransaction() != null) {
                    Assert.fail("transaction still active");
                }
            }
            catch (SystemException e) {
                Assert.fail("Could verify that no transaction is associated", e);
            }
        }
        catch (Exception ignore) {
            // the implementation does not expose a JTA TM as a CDI bean
        }
    }

    /*
     * Locate a UserTransaction bean
     */
    private UserTransaction getUserTransaction(String testName) {
        try {
            return CDI.current().select(UserTransaction.class).get();
        }
        catch (IllegalStateException x) {
            System.out.printf("Skipping test %s. UserTransaction is not available.%n", testName);
            return null;
        }
    }

    /*
     * Create a manager executor that propagates transaction context
     */
    private ManagedExecutor createExecutor(String testName) {
        try {
            return ManagedExecutor.builder()
                    .maxAsync(2)
                    .propagated(ThreadContext.TRANSACTION)
                    .cleared(ThreadContext.ALL_REMAINING)
                    .build();
        }
        catch (IllegalStateException x) {
            System.out.printf("Skipping test %s. Transaction context propagation is not supported.%n",
                    testName);
            return null;
        }
    }
}
