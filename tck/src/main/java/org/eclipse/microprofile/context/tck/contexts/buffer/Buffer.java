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
package org.eclipse.microprofile.context.tck.contexts.buffer;

/**
 * This is a fake context type that is created by the test suite. It associates a StringBuffer with the current thread,
 * accessible via the get/set methods of this class.
 */
public class Buffer {
    public static final String CONTEXT_NAME = "Buffer";

    private static ThreadLocal<StringBuffer> context = ThreadLocal.withInitial(() -> new StringBuffer());

    // use static methods instead of constructing instance
    private Buffer() {
    }

    public static StringBuffer get() {
        return context.get();
    }

    public static void set(StringBuffer buffer) {
        context.set(buffer == null ? new StringBuffer() : buffer);
    }
}
