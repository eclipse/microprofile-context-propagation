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
package org.eclipse.microprofile.context.tck.contexts.label;

/**
 * This is a fake context type that is created by the test suite, which associates an immutable label (a String) with a
 * thread.
 */
public class Label {
    public static final String CONTEXT_NAME = "Label";
    private static ThreadLocal<String> context = ThreadLocal.withInitial(() -> "");

    // use static methods instead of constructing instance
    private Label() {
    }

    /**
     * Get the current 'label' context.
     * 
     * @return current label.
     */
    public static String get() {
        return context.get();
    }

    /**
     * Set the current 'label' context.
     * 
     * @param label
     *            new label.
     */
    public static void set(String label) {
        context.set(label == null ? "" : label);
    }
}
