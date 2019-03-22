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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@ApplicationScoped
class TransactionalService {
    @Inject
    private TransactionalBean bean;

    int getValue() {
        return bean.getValue();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void requiresNew() {
        bean.incrementValue();
    }

    @Transactional(Transactional.TxType.REQUIRED)
    void required() {
        bean.incrementValue();
    }

    @Transactional(Transactional.TxType.MANDATORY)
    void mandatory() {
        System.out.printf("%s: Transactional.TxType.MANDATORY%n", Thread.currentThread());
        bean.incrementValue();
    }

    @Transactional(Transactional.TxType.NEVER)
    void never() {
        bean.incrementValue(); // should throe ContextNotActiveException
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void notSupported() {
        bean.incrementValue(); // should throe ContextNotActiveException
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    void supports() {
        bean.incrementValue();
    }

    @Transactional(Transactional.TxType.REQUIRED)
    int testAsync(ManagedExecutor executor) {
        int currentValue = bean.getValue();
        CompletableFuture<Void> stage;
        try {
            stage = executor.runAsync(this::required);
        }
        catch (IllegalStateException x) {
            System.out.println("Propagation of active transactions is not supported. Skipping test.");
            return JTACDITest.UNSUPPORTED;
        }

        try {
            stage.join();
        }
        catch (CompletionException x) {
            if (x.getCause() instanceof IllegalStateException) {
                System.out.println("Propagation of active transactions to multiple threads in parallel is not supported. Skipping test.");
                return JTACDITest.UNSUPPORTED;
            }
            else {
                throw x;
            }
        }

        return bean.getValue() - currentValue;
    }

    @Transactional(Transactional.TxType.REQUIRED) // run the method in transaction scope
    int testConcurrentTransactionPropagation(ManagedExecutor executor) {
        int currentValue = bean.getValue();

        // run two concurrent transactional bean updates
        CompletableFuture<Void> stage0;
        try {
            stage0 = executor.runAsync(this::required);
        }
        catch (IllegalStateException x) {
            System.out.println("Propagation of active transactions is not supported. Skipping test.");
            return JTACDITest.UNSUPPORTED;
        }
        CompletableFuture<Void> stage1 = executor.runAsync(this::required);

        try {
            CompletableFuture.allOf(stage0, stage1).join();
        }
        catch (CompletionException x) {
            if (x.getCause() instanceof IllegalStateException) {
                System.out.println("Propagation of active transactions to multiple threads in parallel is not supported. Skipping test.");
                return JTACDITest.UNSUPPORTED;
            }
            else {
                throw x;
            }
        }

        return bean.getValue() - currentValue;
    }
}
