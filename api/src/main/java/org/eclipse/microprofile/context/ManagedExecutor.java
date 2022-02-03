/*
 * Copyright (c) 2018,2019 Contributors to the Eclipse Foundation
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
package org.eclipse.microprofile.context;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import org.eclipse.microprofile.context.spi.ContextManager;
import org.eclipse.microprofile.context.spi.ContextManagerProvider;

/**
 * <p>
 * A container-managed executor service that creates instances of CompletableFuture, which it backs as the default
 * asynchronous execution facility, both for the CompletableFuture itself as well as all dependent stages created from
 * it, as well as all dependent stages created from those, and so on.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 * 
 * <pre>
 * <code>ManagedExecutor executor = ManagedExecutor.builder().propagated(ThreadContext.APPLICATION).cleared(ThreadContext.ALL_REMAINING).build();
 * ...
 * CompletableFuture&lt;Integer&gt; future = executor
 *    .supplyAsync(supplier)
 *    .thenApplyAsync(function1)
 *    .thenApply(function2)
 *    ...
 * </code>
 * </pre>
 *
 * <p>
 * This specification allows for managed executors that propagate thread context as well as for those that do not, which
 * might offer better performance. If thread context propagation is desired only for specific stages, or if you wish to
 * override thread context propagation for specific stages, the <code>ThreadContext.contextual*</code> API methods can
 * be used to propagate thread context to individual actions.
 * </p>
 *
 * <p>
 * Example of single action with context propagation:
 * </p>
 * 
 * <pre>
 * CompletableFuture&lt;?&gt; future = executor
 *    .runAsync(runnable1)
 *    .thenRun(threadContext.contextualRunnable(runnable2))
 *    .thenRunAsync(runnable3)
 *    ...
 * </pre>
 *
 * <p>
 * Managed executors that capture and propagate thread context must do so in a consistent and predictable manner, which
 * is defined as follows,
 * </p>
 *
 * <ul>
 * <li>If the supplied action is already contextual (for example,
 * <code>threadContext.contextualFunction(function1))</code>, then the action runs with the already-captured context.
 * </li>
 * <li>Otherwise, each type of thread context is either {@link Builder#propagated propagated} from the thread that
 * creates the completion stage or marked to be {@link Builder#cleared cleared}, according to the configuration of the
 * managed executor that is the default asynchronous execution facility for the new stage and its parent stage. In the
 * case that a managed executor is supplied as the <code>executor</code> argument to a <code>*Async</code> method, the
 * supplied managed executor is used to run the action, but not to determine thread context propagation and clearing.
 * </li>
 * </ul>
 *
 * <p>
 * Each type of thread context is applied (either as cleared or as previously captured) to the thread that runs the
 * action. The applied thread context is removed after the action completes, whether successfully or exceptionally,
 * restoring the thread's prior context.
 * </p>
 *
 * <p>
 * When dependent stages are created from the completion stage, and likewise from any dependent stages created from
 * those, and so on, thread context is captured or cleared in the same manner. This guarantees that the action performed
 * by each stage always runs under the thread context of the code that creates the stage, unless the user explicitly
 * overrides this by supplying a pre-contextualized action.
 * </p>
 *
 * <p>
 * Thread context is similarly captured, cleared, applied, and afterward restored for the ExecutorService methods:
 * <code>execute</code>, <code>invokeAll</code>, <code>invokeAny</code>, <code>submit</code>
 * </p>
 *
 * <p>
 * This interface is intentionally kept compatible with ManagedExecutorService, with the hope that its methods might one
 * day be contributed to that specification.
 * </p>
 *
 * <p>
 * Managed executors that are created with the {@link Builder} or created for injection into applications via CDI must
 * support the life cycle methods, including: awaitTermination, isShutdown, isTerminated, shutdown, shutdownNow. The
 * application must invoke <code>shutdown</code> or <code>shutdownNow</code> to terminate the life cycle of each managed
 * executor that it creates, once the managed executor is no longer needed. When the application stops, the container
 * invokes <code>shutdownNow</code> for any remaining managed executors that the application did not already shut down.
 * The shutdown methods signal the managed executor that it does not need to remain active to service subsequent
 * requests and allow the container to properly clean up associated resources.
 * </p>
 *
 * <p>
 * Managed executors which have a life cycle that is scoped to the container, including those obtained via mechanisms
 * defined by EE Concurrency, must raise IllegalStateException upon invocation of the aforementioned life cycle methods,
 * in order to preserve compatibility with that specification.
 * </p>
 * 
 * <p>
 * Managed executors can forward all contextualised async tasks to a backing executor service if set with
 * {@link ContextManager.Builder#withDefaultExecutorService(ExecutorService)}. Otherwise, a new backing executor service
 * may be created, unless the implementation has its own default executor service.
 * </p>
 */
public interface ManagedExecutor extends ExecutorService {
    /**
     * Creates a new {@link Builder} instance.
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return ContextManagerProvider.instance().getContextManager().newManagedExecutorBuilder();
    }

    /**
     * <p>
     * Builder for {@link ManagedExecutor} instances.
     * </p>
     *
     * <p>
     * Example usage:
     * </p>
     * 
     * <pre>
     * <code> ManagedExecutor executor = ManagedExecutor.builder()
     *                                                  .maxAsync(5)
     *                                                  .maxQueued(20)
     *                                                  .propagated(ThreadContext.SECURITY)
     *                                                  .build();
     * ...
     * </code>
     * </pre>
     */
    public interface Builder {
        /**
         * <p>
         * Builds a new {@link ManagedExecutor} with the configuration that this builder represents as of the point in
         * time when this method is invoked.
         * </p>
         *
         * <p>
         * After {@link #build} is invoked, the builder instance retains its configuration and may be further updated to
         * represent different configurations and build additional {@link ManagedExecutor} instances.
         * </p>
         *
         * <p>
         * All created instances of {@link ManagedExecutor} are destroyed when the application is stopped. The container
         * automatically shuts down these managed executors.
         * </p>
         *
         * @return new instance of {@link ManagedExecutor}.
         * @throws IllegalStateException
         *             for any of the following error conditions
         *             <ul>
         *             <li>if one or more of the same context types appear in both the {@link #cleared} set and the
         *             {@link #propagated} set</li>
         *             <li>if a thread context type that is configured to be {@link #cleared} or {@link #propagated} is
         *             unavailable</li>
         *             <li>if context configuration is neither specified on the builder nor via MicroProfile Config, and
         *             the builder implementation lacks vendor-specific defaults of its own.</li>
         *             <li>if more than one provider provides the same thread context
         *             {@link org.eclipse.microprofile.context.spi.ThreadContextProvider#getThreadContextType type}</li>
         *             </ul>
         */
        ManagedExecutor build();

        /**
         * <p>
         * Defines the set of thread context types to clear from the thread where the action or task executes. The
         * previous context is resumed on the thread after the action or task ends.
         * </p>
         *
         * <p>
         * This set replaces the <code>cleared</code> set that was previously specified on the builder instance, if any.
         * </p>
         *
         * <p>
         * For example, if the user specifies {@link ThreadContext#TRANSACTION} in this set, then a transaction is not
         * active on the thread when the action or task runs, such that each action or task is able to independently
         * start and end its own transactional work.
         * </p>
         *
         * <p>
         * {@link ThreadContext#ALL_REMAINING} is automatically appended to the set of cleared context if the
         * {@link #propagated} set does not include {@link ThreadContext#ALL_REMAINING}.
         * </p>
         *
         * <p>
         * Constants for specifying some of the core context types are provided on {@link ThreadContext}. Other thread
         * context types must be defined by the specification that defines the context type or by a related MicroProfile
         * specification.
         * </p>
         *
         * <p>
         * The MicroProfile Config property, <code>mp.context.ManagedExecutor.cleared</code>, establishes a default that
         * is used if no value is otherwise specified. The value of the MicroProfile Config property can be the empty
         * string or a comma separated list of context type constant values.
         * </p>
         *
         * @param types
         *            types of thread context to clear from threads that run actions and tasks.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder cleared(String... types);

        /**
         * <p>
         * Defines the set of thread context types to capture from the thread that creates a dependent stage (or that
         * submits a task) and which to propagate to the thread where the action or task executes.
         * </p>
         *
         * <p>
         * This set replaces the <code>propagated</code> set that was previously specified on the builder instance, if
         * any.
         * </p>
         *
         * <p>
         * Constants for specifying some of the core context types are provided on {@link ThreadContext}. Other thread
         * context types must be defined by the specification that defines the context type or by a related MicroProfile
         * specification.
         * </p>
         *
         * <p>
         * The MicroProfile Config property, <code>mp.context.ManagedExecutor.propagated</code>, establishes a default
         * that is used if no value is otherwise specified. The value of the MicroProfile Config property can be the
         * empty string or a comma separated list of context type constant values.
         * </p>
         *
         * <p>
         * Thread context types which are not otherwise included in this set are cleared from the thread of execution
         * for the duration of the action or task.
         * </p>
         *
         * @param types
         *            types of thread context to capture and propagate.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder propagated(String... types);

        /**
         * <p>
         * Establishes an upper bound on the number of async completion stage actions and async executor tasks that can
         * be running at any given point in time. There is no guarantee that async actions or tasks will start running
         * immediately, even when the <code>maxAsync</code> constraint has not get been reached. Async actions and tasks
         * remain queued until the <code>ManagedExecutor</code> starts executing them.
         * </p>
         *
         * <p>
         * The default value of <code>-1</code> indicates no upper bound, although practically, resource constraints of
         * the system will apply. You can switch the default by specifying the MicroProfile Config property,
         * <code>mp.context.ManagedExecutor.maxAsync</code>.
         * </p>
         *
         * @param max
         *            upper bound on async completion stage actions and executor tasks.
         * @return the same builder instance upon which this method is invoked.
         * @throws IllegalArgumentException
         *             if max is 0 or less than -1.
         */
        Builder maxAsync(int max);

        /**
         * <p>
         * Establishes an upper bound on the number of async actions and async tasks that can be queued up for
         * execution. Async actions and tasks are rejected if no space in the queue is available to accept them.
         * </p>
         *
         * <p>
         * The default value of <code>-1</code> indicates no upper bound, although practically, resource constraints of
         * the system will apply. You can switch the default by specifying the MicroProfile Config property,
         * <code>mp.context.ManagedExecutor.maxQueued</code>.
         * </p>
         *
         * @param max
         *            upper bound on async actions and tasks that can be queued.
         * @return the same builder instance upon which this method is invoked.
         * @throws IllegalArgumentException
         *             if max is 0 or less than -1.
         */
        Builder maxQueued(int max);
    }

    /**
     * <p>
     * Returns a new CompletableFuture that is already completed with the specified value.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param value
     *            result with which the completable future is completed.
     * @param <U>
     *            result type of the completable future.
     * @return the new completable future.
     */
    <U> CompletableFuture<U> completedFuture(U value);

    /**
     * <p>
     * Returns a new CompletionStage that is already completed with the specified value.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param value
     *            result with which the completable future is completed.
     * @param <U>
     *            result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletionStage<U> completedStage(U value);

    /**
     * <p>
     * Returns a new CompletableFuture that is already exceptionally completed with the specified Throwable.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param ex
     *            exception or error with which the completable future is completed.
     * @param <U>
     *            result type of the completable future.
     * @return the new completable future.
     */
    <U> CompletableFuture<U> failedFuture(Throwable ex);

    /**
     * <p>
     * Returns a new CompletionStage that is already exceptionally completed with the specified Throwable.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param ex
     *            exception or error with which the stage is completed.
     * @param <U>
     *            result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletionStage<U> failedStage(Throwable ex);

    /**
     * <p>
     * Returns a new incomplete <code>CompletableFuture</code>.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param <U>
     *            result type of the completion stage.
     * @return the new completion stage.
     */
    <U> CompletableFuture<U> newIncompleteFuture();

    /**
     * <p>
     * Returns a new CompletableFuture that is completed by a task running in this executor after it runs the given
     * action.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param runnable
     *            the action to run before completing the returned completion stage.
     * @return the new completion stage.
     */
    CompletableFuture<Void> runAsync(Runnable runnable);

    /**
     * <p>
     * Returns a new CompletableFuture that is completed by a task running in this executor after it runs the given
     * action.
     * </p>
     *
     * <p>
     * This executor is the default asynchronous execution facility for the new completion stage that is returned by
     * this method and all dependent stages that are created from it, and all dependent stages that are created from
     * those, and so forth.
     * </p>
     *
     * @param <U>
     *            result type of the supplier and completion stage.
     * @param supplier
     *            an action returning the value to be used to complete the returned completion stage.
     * @return the new completion stage.
     */
    <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier);

    /**
     * <p>
     * Returns a new <code>CompletableFuture</code> that is completed by the completion of the specified stage.
     * </p>
     *
     * <p>
     * The new completable future is backed by the ManagedExecutor upon which copy is invoked, which serves as the
     * default asynchronous execution facility for the new stage and all dependent stages created from it, and so forth.
     * </p>
     *
     * <p>
     * When dependent stages are created from the new completable future, thread context is captured and/or cleared by
     * the ManagedExecutor. This guarantees that the action performed by each stage always runs under the thread context
     * of the code that creates the stage, unless the user explicitly overrides by supplying a pre-contextualized
     * action.
     * </p>
     *
     * <p>
     * Invocation of this method does not impact thread context propagation for the supplied completable future or any
     * dependent stages created from it, other than the new dependent completable future that is created by this method.
     * </p>
     *
     * @param <T>
     *            completable future result type.
     * @param stage
     *            a completable future whose completion triggers completion of the new completable future that is
     *            created by this method.
     * @return the new completable future.
     */
    <T> CompletableFuture<T> copy(CompletableFuture<T> stage);

    /**
     * <p>
     * Returns a new <code>CompletionStage</code> that is completed by the completion of the specified stage.
     * </p>
     *
     * <p>
     * The new completion stage is backed by the ManagedExecutor upon which copy is invoked, which serves as the default
     * asynchronous execution facility for the new stage and all dependent stages created from it, and so forth.
     * </p>
     *
     * <p>
     * When dependent stages are created from the new completion stage, thread context is captured and/or cleared by the
     * ManagedExecutor. This guarantees that the action performed by each stage always runs under the thread context of
     * the code that creates the stage, unless the user explicitly overrides by supplying a pre-contextualized action.
     * </p>
     *
     * <p>
     * Invocation of this method does not impact thread context propagation for the supplied stage or any dependent
     * stages created from it, other than the new dependent completion stage that is created by this method.
     * </p>
     *
     * @param <T>
     *            completion stage result type.
     * @param stage
     *            a completion stage whose completion triggers completion of the new stage that is created by this
     *            method.
     * @return the new completion stage.
     */
    <T> CompletionStage<T> copy(CompletionStage<T> stage);

    /**
     * Returns a <code>ThreadContext</code> which has the same propagation settings as this
     * <code>ManagedExecutor</code>, which uses this <code>ManagedExecutor</code> as its default executor.
     * 
     * @return a ThreadContext with the same propagation settings as this ManagedExecutor.
     */
    ThreadContext getThreadContext();
}
