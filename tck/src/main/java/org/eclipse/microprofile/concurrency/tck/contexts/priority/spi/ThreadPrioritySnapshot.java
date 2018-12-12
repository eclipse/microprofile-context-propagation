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
package org.eclipse.microprofile.concurrency.tck.contexts.priority.spi;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.concurrent.spi.ThreadContextController;
import org.eclipse.microprofile.concurrent.spi.ThreadContextSnapshot;

/**
 * Represents a saved copy of 'thread priority' context.
 */
public class ThreadPrioritySnapshot implements ThreadContextSnapshot {
    private final int priority;

    ThreadPrioritySnapshot(int priority) {
        this.priority = priority;
    }

    /**
     * Apply the saved thread priority to the current thread, but
     * first storing a copying of the thread priority that was previously on the thread
     * so that the previous thread priority can later be restored via the returned
     * ThreadContextController.
     */
    @Override
    public ThreadContextController begin() {
        Thread thread = Thread.currentThread();
        int priorityToRestore = thread.getPriority();
        AtomicBoolean restored = new AtomicBoolean();

        ThreadContextController contextRestorer = () -> {
            if (restored.compareAndSet(false, true)) {
                thread.setPriority(priorityToRestore);
            }
            else {
                throw new IllegalStateException();
            }
        };

        thread.setPriority(priority);

        return contextRestorer;
    }
}
