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

package com.scivicslab.pojoactor.core;

import static org.junit.jupiter.api.Assertions.*;
import static com.scivicslab.pojoactor.core.ActionArgs.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ActionArgs} utility class.
 *
 * @author devteam@scivicslab.com
 * @since 2.14.0
 */
@DisplayName("ActionArgs")
class ActionArgsTest {

    // ========================================================================
    // Array format tests
    // ========================================================================

    @Nested
    @DisplayName("Array Format - asArray()")
    class AsArrayTests {

        @Test
        @DisplayName("Should parse valid JSON array")
        void shouldParseValidJsonArray() {
            JSONArray result = asArray("[\"a\", \"b\", \"c\"]");
            assertEquals(3, result.length());
            assertEquals("a", result.getString(0));
            assertEquals("b", result.getString(1));
            assertEquals("c", result.getString(2));
        }

        @Test
        @DisplayName("Should return empty array for null")
        void shouldReturnEmptyArrayForNull() {
            JSONArray result = asArray(null);
            assertEquals(0, result.length());
        }

        @Test
        @DisplayName("Should return empty array for empty string")
        void shouldReturnEmptyArrayForEmptyString() {
            JSONArray result = asArray("");
            assertEquals(0, result.length());
        }

        @Test
        @DisplayName("Should return empty array for '[]'")
        void shouldReturnEmptyArrayForEmptyBrackets() {
            JSONArray result = asArray("[]");
            assertEquals(0, result.length());
        }

        @Test
        @DisplayName("Should return empty array for invalid JSON")
        void shouldReturnEmptyArrayForInvalidJson() {
            JSONArray result = asArray("not valid json");
            assertEquals(0, result.length());
        }
    }

    @Nested
    @DisplayName("Array Format - getString()")
    class GetStringFromArrayTests {

        @Test
        @DisplayName("Should get string at index")
        void shouldGetStringAtIndex() {
            assertEquals("first", getString("[\"first\", \"second\"]", 0));
            assertEquals("second", getString("[\"first\", \"second\"]", 1));
        }

        @Test
        @DisplayName("Should return empty string for out of bounds")
        void shouldReturnEmptyStringForOutOfBounds() {
            assertEquals("", getString("[\"a\"]", 5));
            assertEquals("", getString("[\"a\"]", -1));
        }

        @Test
        @DisplayName("Should return default value when specified")
        void shouldReturnDefaultValue() {
            assertEquals("default", getString("[\"a\"]", 5, "default"));
        }

        @Test
        @DisplayName("Should handle empty args")
        void shouldHandleEmptyArgs() {
            assertEquals("", getString("[]", 0));
            assertEquals("", getString(null, 0));
            assertEquals("", getString("", 0));
        }
    }

    @Nested
    @DisplayName("Array Format - getFirst()")
    class GetFirstTests {

        @Test
        @DisplayName("Should get first element")
        void shouldGetFirstElement() {
            assertEquals("hello", getFirst("[\"hello\", \"world\"]"));
        }

        @Test
        @DisplayName("Should return empty string for empty array")
        void shouldReturnEmptyStringForEmptyArray() {
            assertEquals("", getFirst("[]"));
            assertEquals("", getFirst(null));
        }

        @Test
        @DisplayName("Should handle single element")
        void shouldHandleSingleElement() {
            assertEquals("only", getFirst("[\"only\"]"));
        }
    }

    @Nested
    @DisplayName("Array Format - getInt()")
    class GetIntFromArrayTests {

        @Test
        @DisplayName("Should get integer at index")
        void shouldGetIntAtIndex() {
            assertEquals(42, getInt("[42, 100]", 0));
            assertEquals(100, getInt("[42, 100]", 1));
        }

        @Test
        @DisplayName("Should return 0 for out of bounds")
        void shouldReturnZeroForOutOfBounds() {
            assertEquals(0, getInt("[1]", 5));
        }

        @Test
        @DisplayName("Should return default value")
        void shouldReturnDefaultValue() {
            assertEquals(99, getInt("[1]", 5, 99));
        }

        @Test
        @DisplayName("Should handle string numbers")
        void shouldHandleStringNumbers() {
            // JSON parser should handle "42" as string containing number
            assertEquals(42, getInt("[\"42\"]", 0));
        }
    }

    @Nested
    @DisplayName("Array Format - getLong()")
    class GetLongFromArrayTests {

        @Test
        @DisplayName("Should get long at index")
        void shouldGetLongAtIndex() {
            assertEquals(9999999999L, getLong("[9999999999]", 0));
        }

        @Test
        @DisplayName("Should return default for missing")
        void shouldReturnDefaultForMissing() {
            assertEquals(123L, getLong("[]", 0, 123L));
        }
    }

    @Nested
    @DisplayName("Array Format - getDouble()")
    class GetDoubleFromArrayTests {

        @Test
        @DisplayName("Should get double at index")
        void shouldGetDoubleAtIndex() {
            assertEquals(3.14, getDouble("[3.14]", 0), 0.001);
        }

        @Test
        @DisplayName("Should return default for missing")
        void shouldReturnDefaultForMissing() {
            assertEquals(2.5, getDouble("[]", 0, 2.5), 0.001);
        }
    }

    @Nested
    @DisplayName("Array Format - getBoolean()")
    class GetBooleanFromArrayTests {

        @Test
        @DisplayName("Should get boolean at index")
        void shouldGetBooleanAtIndex() {
            assertTrue(getBoolean("[true, false]", 0));
            assertFalse(getBoolean("[true, false]", 1));
        }

        @Test
        @DisplayName("Should return false for out of bounds")
        void shouldReturnFalseForOutOfBounds() {
            assertFalse(getBoolean("[true]", 5));
        }

        @Test
        @DisplayName("Should return default value")
        void shouldReturnDefaultValue() {
            assertTrue(getBoolean("[false]", 5, true));
        }
    }

    @Nested
    @DisplayName("Array Format - length()")
    class LengthTests {

        @Test
        @DisplayName("Should return correct length")
        void shouldReturnCorrectLength() {
            assertEquals(3, length("[1, 2, 3]"));
            assertEquals(0, length("[]"));
            assertEquals(0, length(null));
        }
    }

    // ========================================================================
    // Object format tests
    // ========================================================================

    @Nested
    @DisplayName("Object Format - asObject()")
    class AsObjectTests {

        @Test
        @DisplayName("Should parse valid JSON object")
        void shouldParseValidJsonObject() {
            JSONObject result = asObject("{\"name\": \"test\", \"value\": 42}");
            assertEquals("test", result.getString("name"));
            assertEquals(42, result.getInt("value"));
        }

        @Test
        @DisplayName("Should return empty object for null")
        void shouldReturnEmptyObjectForNull() {
            JSONObject result = asObject(null);
            assertEquals(0, result.length());
        }

        @Test
        @DisplayName("Should return empty object for '{}'")
        void shouldReturnEmptyObjectForEmptyBraces() {
            JSONObject result = asObject("{}");
            assertEquals(0, result.length());
        }

        @Test
        @DisplayName("Should return empty object for invalid JSON")
        void shouldReturnEmptyObjectForInvalidJson() {
            JSONObject result = asObject("not valid json");
            assertEquals(0, result.length());
        }
    }

    @Nested
    @DisplayName("Object Format - getString(key)")
    class GetStringFromObjectTests {

        @Test
        @DisplayName("Should get string by key")
        void shouldGetStringByKey() {
            assertEquals("hello", getString("{\"msg\": \"hello\"}", "msg"));
        }

        @Test
        @DisplayName("Should return empty string for missing key")
        void shouldReturnEmptyStringForMissingKey() {
            assertEquals("", getString("{\"a\": \"b\"}", "missing"));
        }

        @Test
        @DisplayName("Should return default value")
        void shouldReturnDefaultValue() {
            assertEquals("default", getString("{}", "key", "default"));
        }
    }

    @Nested
    @DisplayName("Object Format - getInt(key)")
    class GetIntFromObjectTests {

        @Test
        @DisplayName("Should get integer by key")
        void shouldGetIntByKey() {
            assertEquals(8080, getInt("{\"port\": 8080}", "port"));
        }

        @Test
        @DisplayName("Should return 0 for missing key")
        void shouldReturnZeroForMissingKey() {
            assertEquals(0, getInt("{}", "port"));
        }

        @Test
        @DisplayName("Should return default value")
        void shouldReturnDefaultValue() {
            assertEquals(80, getInt("{}", "port", 80));
        }
    }

    @Nested
    @DisplayName("Object Format - getLong(key)")
    class GetLongFromObjectTests {

        @Test
        @DisplayName("Should get long by key")
        void shouldGetLongByKey() {
            assertEquals(9999999999L, getLong("{\"big\": 9999999999}", "big"));
        }
    }

    @Nested
    @DisplayName("Object Format - getDouble(key)")
    class GetDoubleFromObjectTests {

        @Test
        @DisplayName("Should get double by key")
        void shouldGetDoubleByKey() {
            assertEquals(3.14159, getDouble("{\"pi\": 3.14159}", "pi"), 0.00001);
        }
    }

    @Nested
    @DisplayName("Object Format - getBoolean(key)")
    class GetBooleanFromObjectTests {

        @Test
        @DisplayName("Should get boolean by key")
        void shouldGetBooleanByKey() {
            assertTrue(getBoolean("{\"enabled\": true}", "enabled"));
            assertFalse(getBoolean("{\"enabled\": false}", "enabled"));
        }

        @Test
        @DisplayName("Should return default value")
        void shouldReturnDefaultValue() {
            assertTrue(getBoolean("{}", "flag", true));
        }
    }

    @Nested
    @DisplayName("Object Format - hasKey()")
    class HasKeyTests {

        @Test
        @DisplayName("Should return true for existing key")
        void shouldReturnTrueForExistingKey() {
            assertTrue(hasKey("{\"name\": \"test\"}", "name"));
        }

        @Test
        @DisplayName("Should return false for missing key")
        void shouldReturnFalseForMissingKey() {
            assertFalse(hasKey("{\"name\": \"test\"}", "other"));
        }

        @Test
        @DisplayName("Should return false for empty object")
        void shouldReturnFalseForEmptyObject() {
            assertFalse(hasKey("{}", "any"));
        }
    }

    // ========================================================================
    // Validation tests
    // ========================================================================

    @Nested
    @DisplayName("Type Detection")
    class TypeDetectionTests {

        @Test
        @DisplayName("isArray() should detect array format")
        void isArrayShouldDetectArrayFormat() {
            assertTrue(isArray("[\"a\", \"b\"]"));
            assertTrue(isArray("[]"));
            assertTrue(isArray("[1]"));
            assertFalse(isArray("{\"a\":1}"));
            assertFalse(isArray("{}"));
            assertFalse(isArray(null));
            assertFalse(isArray(""));
        }

        @Test
        @DisplayName("isObject() should detect object format")
        void isObjectShouldDetectObjectFormat() {
            assertTrue(isObject("{\"a\":1}"));
            assertTrue(isObject("{}"));
            assertTrue(isObject("{\"host\":\"server\",\"port\":8080}"));
            assertFalse(isObject("[1, 2]"));
            assertFalse(isObject("[]"));
            assertFalse(isObject(null));
            assertFalse(isObject(""));
        }

        @Test
        @DisplayName("Type detection with whitespace")
        void typeDetectionWithWhitespace() {
            assertTrue(isArray("  [1, 2]"));
            assertTrue(isObject("  {\"a\":1}"));
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("hasAtLeast() should check array length")
        void hasAtLeastShouldCheckArrayLength() {
            assertTrue(hasAtLeast("[1, 2, 3]", 2));
            assertTrue(hasAtLeast("[1, 2, 3]", 3));
            assertFalse(hasAtLeast("[1, 2, 3]", 4));
            assertFalse(hasAtLeast("[]", 1));
        }

        @Test
        @DisplayName("isEmpty() should detect empty args")
        void isEmptyShouldDetectEmptyArgs() {
            assertTrue(isEmpty(null));
            assertTrue(isEmpty(""));
            assertTrue(isEmpty("[]"));
            assertTrue(isEmpty("{}"));
            assertFalse(isEmpty("[1]"));
            assertFalse(isEmpty("{\"a\":1}"));
        }

        @Test
        @DisplayName("isNotEmpty() should be inverse of isEmpty()")
        void isNotEmptyShouldBeInverseOfIsEmpty() {
            assertFalse(isNotEmpty(null));
            assertFalse(isNotEmpty("[]"));
            assertTrue(isNotEmpty("[1]"));
        }
    }

    // ========================================================================
    // ParsedArgs unified parsing
    // ========================================================================

    @Nested
    @DisplayName("ParsedArgs - Unified Parsing")
    class ParsedArgsTests {

        @Test
        @DisplayName("Should parse array format")
        void shouldParseArrayFormat() {
            ActionArgs.ParsedArgs p = ActionArgs.parse("[\"hello\", \"world\"]");

            assertTrue(p.isArray());
            assertFalse(p.isObject());
            assertEquals(2, p.length());
            assertEquals("hello", p.get(0));
            assertEquals("world", p.get(1));
        }

        @Test
        @DisplayName("Should parse object format")
        void shouldParseObjectFormat() {
            ActionArgs.ParsedArgs p = ActionArgs.parse("{\"host\":\"server1\",\"port\":8080}");

            assertFalse(p.isArray());
            assertTrue(p.isObject());
            assertEquals("server1", p.get("host"));
            assertEquals(8080, p.getInt("port"));
        }

        @Test
        @DisplayName("Should handle single string (wrapped as array)")
        void shouldHandleSingleString() {
            ActionArgs.ParsedArgs p = ActionArgs.parse("[\"value\"]");

            assertTrue(p.isArray());
            assertEquals("value", p.get(0));
        }

        @Test
        @DisplayName("Should handle empty args")
        void shouldHandleEmptyArgs() {
            ActionArgs.ParsedArgs p = ActionArgs.parse("[]");

            assertTrue(p.isEmpty());
            assertEquals(0, p.length());
            assertEquals("default", p.get(0, "default"));
        }

        @Test
        @DisplayName("Should handle null args")
        void shouldHandleNullArgs() {
            ActionArgs.ParsedArgs p = ActionArgs.parse(null);

            assertTrue(p.isEmpty());
            assertEquals("", p.get(0));
            assertEquals("", p.get("key"));
        }

        @Test
        @DisplayName("Should provide defaults for missing values")
        void shouldProvideDefaultsForMissingValues() {
            ActionArgs.ParsedArgs p = ActionArgs.parse("[\"a\"]");

            assertEquals("default", p.get(5, "default"));
            assertEquals(99, p.getInt(5, 99));
            assertTrue(p.getBoolean(5, true));
        }

        @Test
        @DisplayName("Should provide defaults for missing keys")
        void shouldProvideDefaultsForMissingKeys() {
            ActionArgs.ParsedArgs p = ActionArgs.parse("{\"a\":1}");

            assertEquals("default", p.get("missing", "default"));
            assertEquals(99, p.getInt("missing", 99));
            assertTrue(p.getBoolean("missing", true));
            assertFalse(p.has("missing"));
            assertTrue(p.has("a"));
        }

        @Test
        @DisplayName("Should get raw string")
        void shouldGetRawString() {
            String raw = "[\"test\"]";
            ActionArgs.ParsedArgs p = ActionArgs.parse(raw);

            assertEquals(raw, p.raw());
        }

        @Test
        @DisplayName("Real-world: connect action with named args")
        void realWorldConnectAction() {
            // YAML: arguments: {host: "192.168.1.1", port: 22, ssl: true}
            ActionArgs.ParsedArgs p = ActionArgs.parse(
                "{\"host\":\"192.168.1.1\",\"port\":22,\"ssl\":true}");

            assertEquals("192.168.1.1", p.get("host"));
            assertEquals(22, p.getInt("port"));
            assertTrue(p.getBoolean("ssl"));
        }

        @Test
        @DisplayName("Real-world: copy action with positional args")
        void realWorldCopyAction() {
            // YAML: arguments: ["/src/file.txt", "/dst/file.txt"]
            ActionArgs.ParsedArgs p = ActionArgs.parse(
                "[\"/src/file.txt\",\"/dst/file.txt\"]");

            assertEquals("/src/file.txt", p.get(0));
            assertEquals("/dst/file.txt", p.get(1));
        }
    }

    // ========================================================================
    // Real-world usage scenarios
    // ========================================================================

    @Nested
    @DisplayName("Real-world Scenarios")
    class RealWorldScenarios {

        @Test
        @DisplayName("Single string argument (YAML: arguments: 'value')")
        void singleStringArgument() {
            // YAML: arguments: "hello" becomes ["hello"]
            String args = "[\"hello\"]";
            assertEquals("hello", getFirst(args));
        }

        @Test
        @DisplayName("Multiple arguments (YAML: arguments: [a, b])")
        void multipleArguments() {
            // YAML: arguments: ["source", "dest"]
            String args = "[\"source\", \"dest\"]";
            assertEquals("source", getString(args, 0));
            assertEquals("dest", getString(args, 1));
        }

        @Test
        @DisplayName("Object arguments (YAML: arguments: {key: value})")
        void objectArguments() {
            // YAML: arguments: {hostname: "server1", port: 8080, ssl: true}
            String args = "{\"hostname\": \"server1\", \"port\": 8080, \"ssl\": true}";
            assertEquals("server1", getString(args, "hostname"));
            assertEquals(8080, getInt(args, "port"));
            assertTrue(getBoolean(args, "ssl"));
        }

        @Test
        @DisplayName("Mixed types in array")
        void mixedTypesInArray() {
            String args = "[\"command\", 5, true]";
            assertEquals("command", getString(args, 0));
            assertEquals(5, getInt(args, 1));
            assertTrue(getBoolean(args, 2));
        }

        @Test
        @DisplayName("Validation before processing")
        void validationBeforeProcessing() {
            String args = "[\"src\", \"dst\"]";

            if (!hasAtLeast(args, 2)) {
                fail("Should have at least 2 arguments");
            }

            String src = getString(args, 0);
            String dst = getString(args, 1);

            assertEquals("src", src);
            assertEquals("dst", dst);
        }
    }
}
