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
package org.eclipse.microprofile.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import org.eclipse.microprofile.concurrent.spi.ConcurrencyProvider;

/**
 * <p>A container-managed executor service that creates instances of CompletableFuture,
 * which it backs as the default asynchronous execution facility, both for the
 * CompletableFuture itself as well as all dependent stages created from it,
 * as well as all dependent stages created from those, and so on.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * <code>&commat;Inject</code> ManagedExecutor executor;
 * ...
 * CompletableFuture&lt;Integer&gt; future = executor
 *    .supplyAsync(supplier)
 *    .thenApplyAsync(function1)
 *    .thenApply(function2)
 *    ...
 * </pre>
 *
 * <p>This specification allows for managed executors that do not capture and propagate thread context,
 * which can offer better performance. If thread context propagation is desired only for specific stages,
 * the <code>ThreadContext.contextual*</code> API methods can be used to propagate thread context to
 * individual actions.</p>
 *
 * <p>Example of single action with context propagation:</p>
 * <pre>
 * CompletableFuture&lt;?&gt; future = executor
 *    .runAsync(runnable1)
 *    .thenRun(threadContext.contextualRunnable(runnable2))
 *    .thenRunAsync(runnable3)
 *    ...
 * </pre>
 *
 * <p>For managed executors that are defined as capturing and propagating thread context,
 * it must be done in a consistent manner. Thread context is captured from the thread that creates
 * a completion stage and is applied to the thread that runs the action, being removed afterward.
 * When dependent stages are created from the completion stage, and likewise from any dependent stages
 * created from those, and so on, thread context is captured from the thread that creates the dependent
 * stage. This guarantees that the action performed by each stage always runs under the thread context
 * of the code that creates the stage. When applied to the ExecutorService methods,
 * <code>execute</code>, <code>invokeAll</code>, <code>invokeAny</code>, <code>submit</code>,
 * thread context is captured from the thread invoking the request and propagated to thread that runs
 * the task, being removed afterward.</p>
 *
 * <p>This interface is intentionally kept compatible with ManagedExecutorService,
 * with the hope that its methods might one day be contributed to that specification.</p>
 *
 * <p>Managed executors that are created with the {@link Builder} or created for
 * injection into applications via CDI must support the life cycle methods, including:
 * awaitTermination, isShutdown, isTerminated, shutdown, shutdownNow.
 * The application must invoke <code>shutdown</code> or <code>shutdownNow</code> to terminate
 * the life cycle of each managed executor that it creates, once the managed executor is
 * no longer needed. When the application stops, the container invokes <code>shutdownNow</code>
 * for any remaining managed executors that the application did not already shut down.
 * The shutdown methods signal the managed executor that it does not need to remain active to
 * service subsequent requests and allow the container to properly clean up associated resources.</p>
 *
 * <p>Managed executors which have a life cycle that is scoped to the container,
 * including those obtained via mechanisms defined by EE Concurrency, must raise
 * IllegalStateException upon invocation of the aforementioned life cycle methods,
 * in order to preserve compatibility with that specification.</p>
 */
public interface ManagedExecutor extends ExecutorService {
    /**
     * Creates a new {@link Builder} instance.
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return ConcurrencyProvider.instance().getConcurrencyManager().newManagedExecutorBuilder();
    }

    /**
     * <p>Builder for {@link ManagedExecutor} instances.</p>
     *
     * <p>Example usage:</p>
     * <pre><code> ManagedExecutor executor = ManagedExecutor.builder()
     *                                                  .maxAsync(5)
     *                                                  .maxQueued(20)
     *                                                  .propagated(ThreadContext.SECURITY)
     *                                                  .build();
     * ...
     * </code></pre>
     */
    public interface Builder {
        /**
         * <p>Builds a new {@link ManagedExecutor} with the configuration
         * that this builder represents as of the point in time when this method
         * is invoked.</p>
         *
         * <p>After {@link #build} is invoked, the builder instance retains its
         * configuration and may be further updated to represent different
         * configurations and build additional {@link ManagedExecutor}
         * instances.</p>
         *
         * <p>All created instances of {@link ManagedExecutor} are destroyed
         * when the application is stopped. The container automatically shuts down these
         * managed executors.</p>
         *
         * @return new instance of {@link ManagedExecutor}.
         * @throws IllegalStateException for any of the following error conditions
         *         <ul>
         *         <li>if one or more of the same context types appear in both the
         *         {@link #cleared} set and the {@link #propagated} set</li>
         *         <li>if a thread context type that is configured to be
         *         {@link #cleared} or {@link #propagated} is unavailable</li>
         *         <li>if more than one provider provides the same thread context
         *         {@link org.eclipse.microprofile.concurrent.spi.ThreadContextProvider#getThreadContextType type}
         *         </li>
         *         </ul>
         */
        ManagedExecutor build();

        /**
         * <p>Defines the set of thread context types to clear from the thread
         * where the action or task executes. The previous context is resumed
         * on the thread after the action or task ends.</p>
         *
         * <p>This set replaces the <code>cleared</code> set that was previously
         * specified on the builder instance, if any.</p>
         *
         * <p>The default set of cleared thread context types is
         * {@link ThreadContext#TRANSACTION}, which means that a transaction
         * is not active on the thread when the action or task runs, such
         * that each action or task is able to independently start and end
         * its own transactional work.</p>
         *
         * <p>Constants for specifying some of the core context types are provided
         * on {@link ThreadContext}. Other thread context types must be defined
         * by the specification that defines the context type or by a related
         * MicroProfile specification.</p>
         *
         * @param types types of thread context to clear from threads that run
         *        actions and tasks.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder cleared(String... types);

        /**
         * <p>Defines the set of thread context types to capture from the thread
         * that creates a dependent stage (or that submits a task) and which to
         * propagate to the thread where the action or task executes.</p>
         *
         * <p>This set replaces the <code>propagated</code> set that was
         * previously specified on the builder instance, if any.</p>
         *
         * <p>The default set of propagated thread context types is
         * {@link ThreadContext#ALL_REMAINING}, which includes all available
         * thread context types that support capture and propagation to other
         * threads, except for those that are explicitly {@link cleared},
         * which, by default is {@link ThreadContext#TRANSACTION} context,
         * in which case is suspended from the thread that runs the action or
         * task.</p>
         *
         * <p>Constants for specifying some of the core context types are provided
         * on {@link ThreadContext}. Other thread context types must be defined
         * by the specification that defines the context type or by a related
         * MicroProfile specification.</p>
         *
         * <p>Thread context types which are not otherwise included in this set
         * are cleared from the thread of execution for the duration of the
         * action or task.</p>
         *
         * @param types types of thread context to capture and propagate.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder propagated(String... types);

        /**
         * <p>Establishes an upper bound on the number of async completion stage
         * actions and async executor tasks that can be running at any given point
         * in time. There is no guarantee that async actions or tasks will start
         * running immediately, even when the <code>maxAsync</code> constraint has
         * not get been reached. Async actions and tasks remain queued until
         * the <code>ManagedExecutor</code> starts executing them.</p>
         *
         * <p>The default value of <code>-1</code> indicates no upper bound,
         * although practically, resource constraints of the system will apply.</p>
         *
         * @param max upper bound on async completion stage actions and executor tasks.
         * @return the same builder instance upon which this method is invoked.
         * @throws IllegalArgumentException if max is 0 or less than -1.
         */
        Builder maxAsync(int max);

        /**
         * <p>Establishes an upper bound on the number of async actions and async tasks
         * that can be queued up for execution. Async actions and tasks are rejected
         * if no space in the queue is available to accept them.</p>
         *
         * <p>The default value of <code>-1</code> indicates no upper bound,
         * although practically, resource constraints of the system will apply.</p>
         *
         * @param max upper bound on async actions and tasks that can be queued.
         * @return the same builder instance upon which this method is invoked.
         * @throws IllegalArgumentException if max is 0 or less than -1.
         */
        Builder maxQueued(int max);
    }

    /**
     * <p>Returns a new CompletableFuture that is already completed with the specified value.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param <U> result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletableFuture<U> completedFuture(U value);

    /**
     * <p>Returns a new CompletionStage that is already completed with the specified value.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param <U> result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletionStage<U> completedStage(U value);

    /**
     * <p>Returns a new CompletableFuture that is already exceptionally completed with the specified Throwable.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param <U> result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletableFuture<U> failedFuture(Throwable ex);

    /**
     * <p>Returns a new CompletionStage that is already exceptionally completed with the specified Throwable.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param <U> result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletionStage<U> failedStage(Throwable ex);

    /**
     * <p>Returns a new incomplete <code>CompletableFuture</code>.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param <U> result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletableFuture<U> newIncompleteFuture();

    /**
     * <p>Returns a new CompletableFuture that is completed by a task running in this executor
     * after it runs the given action.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param runnable the action to run before completing the returned completion stage.
     * @return the new completion stage.
     */
    CompletableFuture<Void> runAsync(Runnable runnable);

    /**
     * <p>Returns a new CompletableFuture that is completed by a task running in this executor
     * after it runs the given action.</p>
     *
     * <p>This executor is the default asynchronous execution facility for the new completion stage
     * that is returned by this method and all dependent stages that are created from it,
     * and all dependent stages that are created from those, and so forth.</p>
     *
     * @param <U> result type of the supplier and completion stage.
     * @param supplier an action returning the value to be used to complete the returned completion stage.
     * @return the new completion stage.
     */
    <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier);
}