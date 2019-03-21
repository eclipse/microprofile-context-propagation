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
package org.eclipse.microprofile.context.tck.contexts.buffer.spi;

import java.util.Map;

import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrent.spi.ThreadContextProvider;
import org.eclipse.microprofile.concurrent.spi.ThreadContextSnapshot;

/**
 * This is a fake context type that is created by the test suite.
 * It associates a StringBuffer with the current thread, accessible via
 * the static get/set operations of the BufferContext class.
 */
public class BufferContextProvider implements ThreadContextProvider {
    /**
     * Buffer context is considered cleared by associating a new empty StringBuffer with the thread.
     */
    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> props) {
        return new BufferContextSnapshot(null);
    }

    /**
     * Save the buffer instance that is associated with the current thread.
     */
    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> props) {
        return new BufferContextSnapshot(Buffer.get());
    }

    @Override
    public String getThreadContextType() {
        return Buffer.CONTEXT_NAME;
    }
}
