/*
 * Copyright (c) 2018,2020 Contributors to the Eclipse Foundation
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.microprofile.context.spi.ContextManagerProvider;
import org.eclipse.microprofile.context.spi.ContextManager;

/**
 * This interface offers various methods for capturing the context of the current thread
 * and applying it to various interfaces that are commonly used with completion stages
 * and executor services.  This allows you to contextualize specific actions that need
 * access to the context of the creator/submitter of the stage/task.
 *
 * <p>Example usage:</p>
 * <pre>
 * <code>ThreadContext threadContext = ThreadContext.builder()
 *     .propagated(ThreadContext.SECURITY, ThreadContext.APPLICATION)
 *     .cleared(ThreadContext.TRANSACTION)
 *     .unchanged(ThreadContext.ALL_REMAINING)
 *     .build();
 * ...
 * CompletableFuture&lt;Integer&gt; stage2 = stage1.thenApply(threadContext.contextualFunction(function));
 * ...
 * Future&lt;Integer&gt; future = executor.submit(threadContext.contextualCallable(callable));
 * </code>
 * </pre>
 *
 * <p>This interface is intentionally kept compatible with ContextService,
 * with the hope that its methods might one day be contributed to that specification.</p>
 */
public interface ThreadContext {
    /**
     * Creates a new {@link Builder} instance.
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return ContextManagerProvider.instance().getContextManager().newThreadContextBuilder();
    }

    /**
     * <p>Builder for {@link ThreadContext} instances.</p>
     *
     * <p>Example usage:</p>
     * <pre><code> ThreadContext threadContext = ThreadContext.builder()
     *                                                   .propagated(ThreadContext.APPLICATION, ThreadContext.SECURITY)
     *                                                   .unchanged(ThreadContext.TRANSACTION)
     *                                                   .build();
     * ...
     * </code></pre>
     */
    interface Builder {
        /**
         * <p>Builds a new {@link ThreadContext} instance with the
         * configuration that this builder represents as of the point in time when
         * this method is invoked.</p>
         *
         * <p>After {@link #build} is invoked, the builder instance retains its
         * configuration and may be further updated to represent different
         * configurations and build additional <code>ThreadContext</code>
         * instances.</p>
         *
         * <p>All created instances of {@link ThreadContext} are destroyed
         * when the application is stopped. The container automatically shuts down these
         * {@link ThreadContext} instances and raises <code>IllegalStateException</code>
         * to reject subsequent attempts to apply previously captured thread context.</p>
         *
         * @return new instance of {@link ThreadContext}.
         * @throws IllegalStateException for any of the following error conditions
         *         <ul>
         *         <li>if one or more of the same context types appear in multiple
         *         of the following sets:
         *         ({@link #cleared}, {@link #propagated}, {@link #unchanged})</li>
         *         <li>if a thread context type that is configured to be
         *         {@link #cleared} or {@link #propagated} is unavailable</li>
         *         <li>if context configuration is neither specified on the builder
         *         nor via MicroProfile Config, and the builder implementation lacks
         *         vendor-specific defaults of its own.</li>
         *         <li>if more than one <code>ThreadContextProvider</code> has the
         *         same thread context
         *         {@link org.eclipse.microprofile.context.spi.ThreadContextProvider#getThreadContextType type}
         *         </li>
         *         </ul>
         */
        ThreadContext build();

        /**
         * <p>Defines the set of thread context types to clear from the thread
         * where the action or task executes. The previous context is resumed
         * on the thread after the action or task ends.</p>
         *
         * <p>This set replaces the <code>cleared</code> set that was
         * previously specified on the builder instance, if any.</p>
         *
         * <p>For example, if the user specifies
         * {@link ThreadContext#TRANSACTION} in this set, then a transaction
         * is not active on the thread when the action or task runs, such
         * that each action or task is able to independently start and end
         * its own transactional work.</p>
         *
         * <p>{@link ThreadContext#ALL_REMAINING} is automatically appended to the
         * set of cleared context if neither the {@link #propagated} set nor the
         * {@link #unchanged} set include {@link ThreadContext#ALL_REMAINING}.</p>
         *
         * <p>Constants for specifying some of the core context types are provided
         * on {@link ThreadContext}. Other thread context types must be defined
         * by the specification that defines the context type or by a related
         * MicroProfile specification.</p>
         *
         * <p>The MicroProfile Config property, <code>mp.context.ThreadContext.cleared</code>,
         * establishes a default that is used if no value is otherwise specified.
         * The value of the MicroProfile Config property can be the empty string
         * or a comma separated list of context type constant values.</p>
         *
         * @param types types of thread context to clear from threads that run
         *        actions and tasks.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder cleared(String... types);

        /**
         * <p>Defines the set of thread context types to capture from the thread
         * that contextualizes an action or task. This context is later
         * re-established on the thread(s) where the action or task executes.</p>
         *
         * <p>This set replaces the <code>propagated</code> set that was
         * previously specified on the builder instance, if any.</p>
         *
         * <p>Constants for specifying some of the core context types are provided
         * on {@link ThreadContext}. Other thread context types must be defined
         * by the specification that defines the context type or by a related
         * MicroProfile specification.</p>
         *
         * <p>The MicroProfile Config property, <code>mp.context.ThreadContext.propagated</code>,
         * establishes a default that is used if no value is otherwise specified.
         * The value of the MicroProfile Config property can be the empty string
         * or a comma separated list of context type constant values.</p>
         *
         * <p>Thread context types which are not otherwise included in this set or
         * in the {@link #unchanged} set are cleared from the thread of execution
         * for the duration of the action or task.</p>
         *
         * @param types types of thread context to capture and propagate.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder propagated(String... types);

        /**
         * <p>Defines a set of thread context types that are essentially ignored,
         * in that they are neither captured nor are they propagated or cleared
         * from thread(s) that execute the action or task.</p>
         *
         * <p>This set replaces the <code>unchanged</code> set that was previously
         * specified on the builder instance.</p>
         *
         * <p>Constants for specifying some of the core context types are provided
         * on {@link ThreadContext}. Other thread context types must be defined
         * by the specification that defines the context type or by a related
         * MicroProfile specification.</p>
         *
         * <p>The MicroProfile Config property, <code>mp.context.ThreadContext.unchanged</code>,
         * establishes a default that is used if no value is otherwise specified.
         * The value of the MicroProfile Config property can be the empty string
         * or a comma separated list of context type constant values. If a default
         * value is not specified by MicroProfile Config, then the default value
         * is an empty set.</p>
         *
         * <p>The configuration of <code>unchanged</code> context is provided for
         * advanced patterns where it is desirable to leave certain context types
         * on the executing thread.</p>
         *
         * <p>For example, to run under the transaction of the thread of execution,
         * with security context cleared and all other thread contexts propagated:</p>
         * <pre><code> ThreadContext threadContext = ThreadContext.builder()
         *                                                   .unchanged(ThreadContext.TRANSACTION)
         *                                                   .cleared(ThreadContext.SECURITY)
         *                                                   .propagated(ThreadContext.ALL_REMAINING)
         *                                                   .build();
         * ...
         * task = threadContext.contextualRunnable(new MyTransactionlTask());
         * ...
         * // on another thread,
         * tx.begin();
         * ...
         * task.run(); // runs under the transaction due to 'unchanged'
         * tx.commit();
         * </code></pre>
         *
         * @param types types of thread context to leave unchanged on the thread.
         * @return the same builder instance upon which this method is invoked.
         */
        Builder unchanged(String... types);
    }

    /**
     * <p>Identifier for all available thread context types which are
     * not specified individually under <code>cleared</code>,
     * <code>propagated</code>, or <code>unchanged</code>.</p>
     *
     * <p>When using this constant, be aware that bringing in a new
     * context provider or updating levels of an existing context provider
     * might change the set of available thread context types.</p>
     *
     * @see ManagedExecutor.Builder#cleared
     * @see ManagedExecutor.Builder#propagated
     * @see ThreadContext.Builder
     */
    static final String ALL_REMAINING = "Remaining";

    /**
     * Identifier for application context. Application context controls the
     * application component that is associated with a thread.
     * For Jakarta/Java EE applications, application context includes the
     * thread context class loader as well as the java:comp, java:module,
     * and java:app name spaces of the application. An empty/default
     * application context means that the thread is not associated with any
     * application.
     *
     * @see ManagedExecutor.Builder#cleared
     * @see ManagedExecutor.Builder#propagated
     * @see ThreadContext.Builder
     */
    static final String APPLICATION = "Application";

    /**
     * Identifier for CDI context. CDI context controls the availability of CDI
     * scopes. An empty/default CDI context means that the thread does not have
     * access to the scope of the session, request, and so forth that created the
     * contextualized action.
     * 
     * For example, consider the following <code>&#64;RequestScoped</code> resource:
     * 
     * <pre><code>
    &#64;RequestScoped
    public class MyRequestScopedBean {
        public String getState() {
          // returns some request-specific information to the caller
        }
    }
    </code></pre>
     * 
     * CDI context propagation includes request, session and conversation contexts.
     * When CDI context is propagated, all of the above mentioned contexts that are
     * currently active will be available to the contextualized task with preserved 
     * state. <pre><code>
    ManagedExecutor exec = ManagedExecutor.builder()
        .propagated(ThreadContext.CDI, ThreadContext.APPLICATION)
        .build();
    
    &#64;Inject
    MyRequestScopedBean requestBean;
    
    &#64;GET
    public void foo() {
        exec.supplyAsync(() -&gt; {
            String state = reqBean.getState();
            // do some work with the request state
        }).thenApply(more -&gt; {
            // request state also available in future stages
        });
    }
    </code></pre>
     *
     * If CDI context is 'cleared', currently active contexts will still be
     * available to the contextualized task, but their state will be erased.
     * 
     * If CDI context is 'unchanged', access to CDI bean's contextual state 
     * will be non-deterministic. Namely, context may be missing, or context
     * from a different task may be applied instead. This option is discouraged,
     * and only should be used if CDI context is not used in an application.
     *
     * @see ManagedExecutor.Builder#cleared
     * @see ManagedExecutor.Builder#propagated
     * @see ThreadContext.Builder
     */
    static final String CDI = "CDI";

    /**
     * <p>An empty array of thread context.</p>
     *
     * <p>This is provided as a convenience for code that wishes to be more explicit.
     * For example, you can specify <code>builder.propagated(ThreadContext.NONE)</code>
     * rather than <code>builder.propagated(new String[0])</code>
     * or <code>builder.propagated()</code>, all of which have the same meaning.</p>
     *
     * <p>When using MicroProfile Config to specify defaults, the value
     * <code>None</code> indicates an empty array. For example,
     * <pre>mp.context.ThreadContext.unchanged=None</pre>
     * or
     * <pre>mp.context.ManagedExecutor.propagated=None</pre>
     */
    static final String[] NONE = new String[0];

    /**
     * Identifier for security context. Security context controls the credentials
     * that are associated with the thread. An empty/default security context
     * means that the thread is unauthenticated.
     * 
     * @see ManagedExecutor.Builder#cleared
     * @see ManagedExecutor.Builder#propagated
     * @see ThreadContext.Builder
     */
    static final String SECURITY = "Security";

    /**
     * Identifier for transaction context. Transaction context controls the
     * active transaction scope that is associated with the thread.
     * Implementations are not expected to propagate transaction context across
     * threads. Instead, the concept of transaction context is provided for its
     * cleared context, which means the active transaction on the thread
     * is suspended such that a new transaction can be started if so desired.
     * In most cases, the most desirable behavior will be to leave transaction
     * context defaulted to cleared (suspended),
     * in order to prevent dependent actions and tasks from accidentally
     * enlisting in transactions that are on the threads where they happen to
     * run.
     *
     * @see ManagedExecutor.Builder#cleared
     * @see ManagedExecutor.Builder#propagated
     * @see ThreadContext.Builder
     */
    static final String TRANSACTION = "Transaction";

    /**
     * <p>Creates an <code>Executor</code>that runs tasks on the same thread from which
     * <code>execute</code>is invoked but with context that is captured from the thread
     * that invokes <code>currentContextExecutor</code>.</p>
     *
     * <p>Example usage:</p>
     * <pre>
     * <code>Executor contextSnapshot = threadContext.currentContextExecutor();
     * ...
     * // from another thread, or after thread context has changed,
     * contextSnapshot.execute(() -&gt; obj.doSomethingThatNeedsContext());
     * contextSnapshot.execute(() -&gt; doSomethingElseThatNeedsContext(x, y));
     * </code></pre>
     *
     * <p>The returned <code>Executor</code> must raise <code>IllegalArgumentException</code>
     * if an already-contextualized <code>Runnable</code> is supplied to its
     * <code>execute</code> method.</p>
     *
     * @return an executor that wraps the <code>execute</code> method with context.
     */
    Executor currentContextExecutor();

    /**
     * <p>Wraps a <code>Callable</code> with context that is captured from the thread that invokes
     * <code>contextualCallable</code>.</p>
     * 
     * <p>When <code>call</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>call</code> method,
     * then the <code>call</code> method of the provided <code>Callable</code> is invoked.
     * Finally, the previous context is restored on the thread, and the result of the
     * <code>Callable</code> is returned to the invoker.</p> 
     *
     * @param <R> callable result type.
     * @param callable instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>call</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>Callable</code> is supplied to this method.
     */
    <R> Callable<R> contextualCallable(Callable<R> callable);

    /**
     * <p>Wraps a <code>BiConsumer</code> with context that is captured from the thread that invokes
     * <code>contextualConsumer</code>.</p>
     *
     * <p>When <code>accept</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>accept</code> method,
     * then the <code>accept</code> method of the provided <code>BiConsumer</code> is invoked.
     * Finally, the previous context is restored on the thread, and control is returned to the invoker.</p>
     *
     * @param <T> type of first parameter to consumer.
     * @param <U> type of second parameter to consumer.
     * @param consumer instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>accept</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>BiConsumer</code> is supplied to this method.
     */
    <T, U> BiConsumer<T, U> contextualConsumer(BiConsumer<T, U> consumer);

    /**
     * <p>Wraps a <code>Consumer</code> with context that is captured from the thread that invokes
     * <code>contextualConsumer</code>.</p>
     * 
     * <p>When <code>accept</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>accept</code> method,
     * then the <code>accept</code> method of the provided <code>Consumer</code> is invoked.
     * Finally, the previous context is restored on the thread, and control is returned to the invoker.</p> 
     *
     * @param <T> type of parameter to consumer.
     * @param consumer instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>accept</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>Consumer</code> is supplied to this method.
     */
    <T> Consumer<T> contextualConsumer(Consumer<T> consumer);

    /**
     * <p>Wraps a <code>BiFunction</code> with context that is captured from the thread that invokes
     * <code>contextualFunction</code>.</p>
     *
     * <p>When <code>apply</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>apply</code> method,
     * then the <code>apply</code> method of the provided <code>BiFunction</code> is invoked.
     * Finally, the previous context is restored on the thread, and the result of the
     * <code>BiFunction</code> is returned to the invoker.</p>
     *
     * @param <T> type of first parameter to function.
     * @param <U> type of second parameter to function.
     * @param <R> function result type.
     * @param function instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>apply</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>BiFunction</code> is supplied to this method.
     */
    <T, U, R> BiFunction<T, U, R> contextualFunction(BiFunction<T, U, R> function);

    /**
     * <p>Wraps a <code>Function</code> with context that is captured from the thread that invokes
     * <code>contextualFunction</code>.</p>
     * 
     * <p>When <code>apply</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>apply</code> method,
     * then the <code>apply</code> method of the provided <code>Function</code> is invoked.
     * Finally, the previous context is restored on the thread, and the result of the
     * <code>Function</code> is returned to the invoker.</p> 
     *
     * @param <T> type of parameter to function.
     * @param <R> function result type.
     * @param function instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>apply</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>Function</code> is supplied to this method.
     */
    <T, R> Function<T, R> contextualFunction(Function<T, R> function);

    /**
     * <p>Wraps a <code>Runnable</code> with context that is captured from the thread that invokes
     * <code>ContextualRunnable</code>.</p>
     * 
     * <p>When <code>run</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>run</code> method,
     * then the <code>run</code> method of the provided <code>Runnable</code> is invoked.
     * Finally, the previous context is restored on the thread, and control is returned to the invoker.</p> 
     * 
     * @param runnable instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>run</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>Runnable</code> is supplied to this method.
     */
    Runnable contextualRunnable(Runnable runnable);

    /**
     * <p>Wraps a <code>Supplier</code> with context captured from the thread that invokes
     * <code>contextualSupplier</code>.</p>
     * 
     * <p>When <code>supply</code> is invoked on the proxy instance,
     * context is first established on the thread that will run the <code>supply</code> method,
     * then the <code>supply</code> method of the provided <code>Supplier</code> is invoked.
     * Finally, the previous context is restored on the thread, and the result of the
     * <code>Supplier</code> is returned to the invoker.</p> 
     *
     * @param <R> supplier result type.
     * @param supplier instance to contextualize.
     * @return contextualized proxy instance that wraps execution of the <code>supply</code> method with context.
     * @throws IllegalArgumentException if an already-contextualized <code>Supplier</code> is supplied to this method.
     */
    <R> Supplier<R> contextualSupplier(Supplier<R> supplier);

    /**
     * <p>Returns a proxy for one or more interfaces, such that methods run with
     * context that is captured, per the configuration of this <code>ThreadContext</code>,
     * from the thread that invokes <code>onMethodsOf</code>.</p>
     *
     * <p>When interface methods are invoked on the resulting proxy,
     * context is first established on the thread that will run the method,
     * and then the method of the provided instance is invoked.
     * Finally, the previous context is restored on the thread, and the result of the
     * method is returned to the invoker.</p>
     *
     * <p>For example, in the case of,</p>
     * <pre>
     * <code>publisher.subscribe(threadContext.onMethodsOf(subscriber))</code></pre>
     *
     * <p>the context of the thread that invokes <code>onMethodsOf</code> is made available to the
     * subscriber's <code>onSubscribe</code>, <code>onNext</code>, <code>onError</code>, and
     * <code>onComplete</code> methods whenever they are used.</p>
     *
     * @param <T>      an interface that is implemented by the instance.
     * @param <U>      type of supplied instance.
     * @param instance instance to contextualize.
     * @return contextualized proxy for interfaces that are implemented by the supplied instance.
     * @throws IllegalArgumentException if an already-contextualized instance is supplied to this method,
     *                 or if the supplied instance does not implement any interfaces that can be proxied,
     *                 or if an instance of <code>CompletionStage</code> is supplied as the parameter.
     * @throws UnsupportedOperationException if the instance implements <code>java.io.Serializable</code>.
     *                 Serializing and deserializing captured thread context is not supported.
     */
    <T, U extends T> T onMethodsOf(U instance);

    /**
     * <p>Proxies a publisher interface such that it invokes subscriber methods with context that is captured,
     * per the configuration of this <code>ThreadContext</code>, from the thread that invokes
     * <code>onSubscriberMethods</code>.</p>
     *
     * <p>When subscriber methods are invoked by the publisher instance,
     * context is first established on the thread that will run the method,
     * then the corresponding method is invoked on the actual subscriber instance.
     * Finally, the previous context is restored on the thread.</p>
     *
     * <p>For example, in the case of,</p>
     * <pre>
     * <code>threadContext.onSubscriberMethods(publisher);
     * ...
     * publisher.subscribe(subscriber);</code></pre>
     *
     * <p>context of the thread that invokes <code>onSubscriberMethods</code> is made available to the
     * <code>onSubscribe</code>, <code>onNext</code>, <code>onError</code>, and <code>onComplete</code>
     * methods of every subscriber for which <code>publisher.subscribe(subscriber)</code> is
     * subsequently invoked on the proxy publisher instance.</p>
     *
     * @param <P>         a publisher.
     * @param <Publisher> spec interface of publisher (<code>java.util.concurrent.Flow.Publisher</code>
     *                    or <code>org.reactivestreams.Publisher</code>).
     * @param publisher instance to proxy such that thread context is applied to subscribers that are added via the proxy.
     * @return Publisher proxy.
     * @throws IllegalArgumentException if the supplied instance is already a proxy that was created by <code>onSubscriberMethods</code>.
     *                 or if the supplied instance does not implement <code>java.util.concurrent.Flow.Publisher</code>
     *                 or <code>org.reactivestreams.Publisher</code>.
     * @throws UnsupportedOperationException if the publisher instance implements <code>java.io.Serializable</code>.
     *                 Serializing and deserializing captured thread context is not supported.
     */
    <Publisher, P extends Publisher> Publisher onSubscriberMethods(P publisher);

    /**
     * <p>Returns a new <code>CompletableFuture</code> that is completed by the completion of the
     * specified stage.</p>
     *
     * <p>The new completable future will use the same default executor as this ThreadContext,
     * which can be a ManagedExecutor if this ThreadContext was obtained by {@link ManagedExecutor#getThreadContext()}
     * or the default executor service if provided by the platform (which can be done via 
     * {@link ContextManager.Builder#withDefaultExecutorService(ExecutorService)}), or otherwise have no default executor.</p>
     * 
     * <p>If this thread context has no default executor, the new stage and all dependent stages created from it, and so forth,
     * have no default asynchronous execution facility and must raise {@link java.lang.UnsupportedOperationException}
     * for all <code>*Async</code> methods that do not specify an executor. For example,
     * {@link java.util.concurrent.CompletionStage#thenRunAsync(Runnable) thenRunAsync(Runnable)}.</p>
     *
     * <p>When dependent stages are created from the new completable future, thread context is captured
     * and/or cleared as described in the documentation of the {@link ManagedExecutor} class, except that
     * this ThreadContext instance takes the place of the default asynchronous execution facility in
     * supplying the configuration of cleared/propagated context types. This guarantees that the action
     * performed by each stage always runs under the thread context of the code that creates the stage,
     * unless the user explicitly overrides by supplying a pre-contextualized action.</p>
     *
     * <p>Invocation of this method does not impact thread context propagation for the supplied
     * completable future or any dependent stages created from it, other than the new dependent
     * completable future that is created by this method.</p>
     *
     * @param <T> completable future result type.
     * @param stage a completable future whose completion triggers completion of the new completable
     *        future that is created by this method.
     * @return the new completable future.
     */
    <T> CompletableFuture<T> withContextCapture(CompletableFuture<T> stage);

    /**
     * <p>Returns a new <code>CompletionStage</code> that is completed by the completion of the
     * specified stage.</p>
     *
     * <p>The new completion stage will use the same default executor as this ThreadContext,
     * which can be a ManagedExecutor if this ThreadContext was obtained by {@link ManagedExecutor#getThreadContext()}
     * or the default executor service if provided by the platform (which can be done via 
     * {@link ContextManager.Builder#withDefaultExecutorService(ExecutorService)}), or otherwise have no default executor.</p>
     * 
     * <p>If this thread context has no default executor, the new stage and all dependent stages created from it, and so forth,
     * have no default asynchronous execution facility and must raise {@link java.lang.UnsupportedOperationException}
     * for all <code>*Async</code> methods that do not specify an executor. For example,
     * {@link java.util.concurrent.CompletionStage#thenRunAsync(Runnable) thenRunAsync(Runnable)}.</p>
     *
     * <p>When dependent stages are created from the new completion stage, thread context is captured
     * and/or cleared as described in the documentation of the {@link ManagedExecutor} class, except that
     * this ThreadContext instance takes the place of the default asynchronous execution facility in
     * supplying the configuration of cleared/propagated context types. This guarantees that the action
     * performed by each stage always runs under the thread context of the code that creates the stage,
     * unless the user explicitly overrides by supplying a pre-contextualized action.</p>
     *
     * <p>Invocation of this method does not impact thread context propagation for the supplied
     * stage or any dependent stages created from it, other than the new dependent
     * completion stage that is created by this method.</p>
     *
     * @param <T> completion stage result type.
     * @param stage a completion stage whose completion triggers completion of the new stage
     *        that is created by this method.
     * @return the new completion stage.
     */
    <T> CompletionStage<T> withContextCapture(CompletionStage<T> stage);
}