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
package org.eclipse.microprofile.context.tck.contexts.priority.spi;

import java.util.Map;

import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.eclipse.microprofile.concurrent.spi.ThreadContextSnapshot;

/**
 * This is an example context type that is created by the test suite.
 * This context type captures/clears/propagates/restores thread priority (java.lang.Thread.get/setPriority). 
 * This is chosen, not because it is useful in any way, but because the concept of thread priority is simple,
 * well understood, and already built into Java.
 */
public class ThreadPriorityContextProvider implements ThreadContextProvider {
    public static final String THREAD_PRIORITY = "ThreadPriority";

    /**
     * Thread priority context is considered cleared by resetting to normal priority (Thread.NORM_PRIORITY).
     */
    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> props) {
        return new ThreadPrioritySnapshot(Thread.NORM_PRIORITY);
    }

    /**
     * Save the current thread priority.
     */
    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> props) {
        return new ThreadPrioritySnapshot(Thread.currentThread().getPriority());
    }

    @Override
    public String getThreadContextType() {
        return THREAD_PRIORITY;
    }
}
