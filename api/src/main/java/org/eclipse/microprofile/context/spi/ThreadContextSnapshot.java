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
package org.eclipse.microprofile.context.spi;

/**
 * <p>
 * An immutable snapshot of a particular type of thread context.
 * </p>
 *
 * <p>
 * The captured context represented by this snapshot can be applied to any number of threads, including concurrently.
 * </p>
 *
 * <p>
 * Any state that is associated with context applied to a thread should be kept, not within the snapshot, but within the
 * distinct <code>ThreadContextController</code> instance it creates each time it is applied to a thread.
 * </p>
 */
@FunctionalInterface
public interface ThreadContextSnapshot {
    /**
     * <p>
     * Applies the captured thread context snapshot to the current thread and returns a distinct
     * <code>ThreadContextController</code> instance. The <code>ThreadContextController</code> instance tracks the
     * context's life cycle, including any state that is associated with it or that is necessary for restoring the
     * previous context.
     * </p>
     *
     * <p>
     * For each invocation of this method, the invoker (typically a <code>ManagedExecutor</code> or
     * <code>ThreadContext</code> instance) must invoke the <code>endContext</code> method on the corresponding
     * <code>ThreadContextController</code> instance exactly once, such that the previous context is restored on the
     * thread. If the invoker sequentially begins multiple <code>ThreadContextController</code> instances on a thread,
     * it must invoke the corresponding <code>endContext</code> methods in reverse order.
     * </p>
     *
     * @return controller instance representing a single application of this thread context snapshot to a thread.
     */
    ThreadContextController begin();
}