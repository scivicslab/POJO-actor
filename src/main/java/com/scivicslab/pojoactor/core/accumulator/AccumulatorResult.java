/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.pojoactor.core.accumulator;

import java.time.Instant;

/**
 * A result entry in an accumulator.
 *
 * <p>This record represents a single result that has been added to an accumulator,
 * including metadata about when and where it came from.</p>
 *
 * @param <T> the type of the result data
 * @param source the source identifier (e.g., actor name)
 * @param result the result data
 * @param timestamp when the result was added
 *
 * @author devteam@scivicslab.com
 * @since 2.8.0
 */
public record AccumulatorResult<T>(
    String source,
    T result,
    Instant timestamp
) {}
