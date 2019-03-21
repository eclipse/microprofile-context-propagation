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

import org.eclipse.microprofile.context.tck.contexts.buffer.Buffer;
import org.eclipse.microprofile.concurrent.spi.ThreadContextController;
import org.eclipse.microprofile.concurrent.spi.ThreadContextSnapshot;

/**
 * Represents a saved 'buffer' instance.
 */
public class BufferContextSnapshot implements ThreadContextSnapshot {
    private final StringBuffer buffer;

    BufferContextSnapshot(StringBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Apply the requested buffer context to the current thread,
     * first storing a copying of the buffer that was previously associated with the thread,
     * to later be restored via the returned ThreadContextController.
     */
    @Override
    public ThreadContextController begin() {
        ThreadContextController contextRestorer = new BufferContextRestorer();
        Buffer.set(buffer == null ? new StringBuffer() : buffer);
        return contextRestorer;
    }

    // For easier debug
    @Override
    public String toString() {
        String s = "BufferContextSnapshot@" + Integer.toHexString(System.identityHashCode(this));
        if (buffer == null) {
            s += " CLEARED";
        }
        else {
            s += " for StringBuffer@" + Integer.toHexString(System.identityHashCode(buffer)) + ": " + buffer.toString();
        }
        return s;
    }
}
