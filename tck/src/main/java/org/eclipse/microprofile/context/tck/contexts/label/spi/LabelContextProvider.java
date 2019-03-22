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
package org.eclipse.microprofile.context.tck.contexts.label.spi;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.context.tck.contexts.label.Label;
import org.eclipse.microprofile.context.spi.ThreadContextController;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;

/**
 * This is a fake all-in-one context provider that is created by the test suite.
 * This context type captures/clears/propagates/restores an immutable label (a String) that is associated with a thread. 
 */
public class LabelContextProvider implements ThreadContextProvider {
    /**
     * Cleared context is the empty string.
     */
    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> props) {
        return snapshot("");
    }

    /**
     * Save the current context.
     */
    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> props) {
        return snapshot(Label.get());
    }

    @Override
    public String getThreadContextType() {
        return Label.CONTEXT_NAME;
    }

    /**
     * Construct a snapshot of context for the specified 'label'
     */
    private ThreadContextSnapshot snapshot(String label) {
        return () -> {
            String labelToRestore = Label.get();
            AtomicBoolean restored = new AtomicBoolean();

            // Construct an instance that restores the previous context that was on the thread
            // prior to applying the specified 'label' as the new context.
            ThreadContextController contextRestorer = () -> {
                if (restored.compareAndSet(false, true)) {
                    Label.set(labelToRestore);
                }
                else {
                    throw new IllegalStateException();
                }
            };

            Label.set(label);
            return contextRestorer;
        };
    }
}
