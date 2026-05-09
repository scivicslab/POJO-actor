/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package com.scivicslab.pojoactor.core;

/**
 * A type-safe key for storing named attributes on an ActorRef.
 *
 * <p>Declare keys as static constants to establish a vocabulary of actor attributes.
 * This prevents typos and enforces the type contract at the put/get boundary.
 *
 * <pre>{@code
 * static final AttributeKey<Long>   START_TIME  = AttributeKey.of("startTime",  Long.class);
 * static final AttributeKey<String> REQUEST_ID  = AttributeKey.of("requestId",  String.class);
 *
 * ref.putAttribute(START_TIME, System.currentTimeMillis());
 * Long t = ref.getAttribute(START_TIME);
 * }</pre>
 *
 * @param <T> the type of value this key carries
 * @since 2.16.0
 */
public final class AttributeKey<T> {

    private final String name;
    private final Class<T> type;

    private AttributeKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type);
    }

    public String name() { return name; }

    public Class<T> type() { return type; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AttributeKey<?> other)) return false;
        return name.equals(other.name) && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + type.hashCode();
    }

    @Override
    public String toString() {
        return "AttributeKey[" + name + ":" + type.getSimpleName() + "]";
    }
}
