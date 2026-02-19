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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive specification tests for JsonState.
 * Based on specification documents:
 * - 100_JsonStatePutJson_260129_oo01
 * - 101_JsonStateSelect_260129_oo01
 * - 102_JsonStateGetters_260129_oo01
 * - 103_JsonStateRemove_260129_oo01
 * - 104_JsonStateOutput_260129_oo01
 * - 105_JsonStateUtility_260129_oo01
 */
class JsonStateSpecTest {

    // ========================================================================
    // select() Tests - Path Resolution
    // ========================================================================

    @Nested
    @DisplayName("select() - パス解決")
    class SelectTests {

        @Test
        @DisplayName("例1: 空パスでルートノード取得")
        void select_emptyPath_returnsRoot() {
            JsonState state = new JsonState();
            state.put("key", "value");

            JsonNode root1 = state.select("");
            JsonNode root2 = state.select(null);

            assertTrue(root1.has("key"));
            assertTrue(root2.has("key"));
        }

        @Test
        @DisplayName("例2: トップレベルキーの取得")
        void select_topLevelKey_returnsValue() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            JsonNode node1 = state.select("name");
            JsonNode node2 = state.select("$.name");

            assertEquals("Mery", node1.asText());
            assertEquals("Mery", node2.asText());
        }

        @Test
        @DisplayName("例3: ネストしたパスの取得")
        void select_nestedPath_returnsValue() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");

            JsonNode node1 = state.select("user.profile.name");
            JsonNode node2 = state.select("$.user.profile.name");
            JsonNode node3 = state.select("user.profile");

            assertEquals("Mery", node1.asText());
            assertEquals("Mery", node2.asText());
            assertTrue(node3.isObject());
            assertEquals("Mery", node3.get("name").asText());
        }

        @Test
        @DisplayName("例4: 配列インデックスの取得")
        void select_arrayIndex_returnsElement() {
            JsonState state = new JsonState();
            state.put("hosts[0]", "server1");
            state.put("hosts[1]", "server2");

            JsonNode node0 = state.select("hosts[0]");
            JsonNode node1 = state.select("hosts[1]");
            JsonNode array = state.select("hosts");

            assertEquals("server1", node0.asText());
            assertEquals("server2", node1.asText());
            assertTrue(array.isArray());
            assertEquals(2, array.size());
        }

        @Test
        @DisplayName("例5: ネストした配列アクセス")
        void select_nestedArrayAccess_returnsValue() {
            JsonState state = new JsonState();
            state.put("users[0].name", "Alice");
            state.put("users[0].age", 30);
            state.put("users[1].name", "Bob");

            JsonNode name0 = state.select("users[0].name");
            JsonNode age0 = state.select("users[0].age");
            JsonNode user1 = state.select("users[1]");

            assertEquals("Alice", name0.asText());
            assertEquals(30, age0.asInt());
            assertEquals("Bob", user1.get("name").asText());
        }

        @Test
        @DisplayName("例6: 存在しないパスはMissingNode")
        void select_nonexistentPath_returnsMissingNode() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            JsonNode missing1 = state.select("nonexistent");
            JsonNode missing2 = state.select("user.profile.name");
            JsonNode missing3 = state.select("names[0]");

            assertTrue(missing1.isMissingNode());
            assertTrue(missing2.isMissingNode());
            assertTrue(missing3.isMissingNode());
        }

        @Test
        @DisplayName("例7: 配列の範囲外インデックス")
        void select_outOfBoundsIndex_returnsMissingNode() {
            JsonState state = new JsonState();
            state.put("hosts[0]", "server1");

            JsonNode outOfBounds = state.select("hosts[10]");

            assertTrue(outOfBounds.isMissingNode());
        }
    }

    // ========================================================================
    // put() Tests - Value Storage
    // ========================================================================

    @Nested
    @DisplayName("put() - 値の保存")
    class PutTests {

        @Test
        @DisplayName("例1: 文字列の保存")
        void put_string_storesCorrectly() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            assertEquals("Mery", state.getString("name"));
        }

        @Test
        @DisplayName("例2: 数値の保存")
        void put_numbers_storesCorrectly() {
            JsonState state = new JsonState();
            state.put("count", 42);
            state.put("price", 19.99);

            assertEquals(42, state.getInt("count", 0));
            assertEquals(19.99, state.getDouble("price", 0.0), 0.001);
        }

        @Test
        @DisplayName("例3: 真偽値の保存")
        void put_boolean_storesCorrectly() {
            JsonState state = new JsonState();
            state.put("enabled", true);
            state.put("debug", false);

            assertTrue(state.getBoolean("enabled", false));
            assertFalse(state.getBoolean("debug", true));
        }

        @Test
        @DisplayName("例4: ネストしたパスへの保存")
        void put_nestedPath_createsHierarchy() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");
            state.put("user.profile.age", 25);

            assertEquals("Mery", state.getString("user.profile.name"));
            assertEquals(25, state.getInt("user.profile.age", 0));
        }

        @Test
        @DisplayName("例5: 配列インデックスへの保存")
        void put_arrayIndex_createsArray() {
            JsonState state = new JsonState();
            state.put("hosts[0]", "server1");
            state.put("hosts[1]", "server2");

            assertEquals("server1", state.getString("hosts[0]"));
            assertEquals("server2", state.getString("hosts[1]"));
        }

        @Test
        @DisplayName("例6: JSON文字列の保存（オブジェクト）- パースされて構造化される")
        void put_jsonObjectString_parsedAsStructure() {
            JsonState state = new JsonState();
            String jsonString = "{\"cluster\":\"https://k8s.example.com\",\"nodes\":2}";
            state.put("data", jsonString);

            // JSON文字列はパースされ、構造化されたオブジェクトとして保存される
            assertEquals("https://k8s.example.com", state.getString("data.cluster"));
            assertEquals(2, state.getInt("data.nodes", 0));
        }

        @Test
        @DisplayName("例7: JSON文字列の保存（配列）- パースされて構造化される")
        void put_jsonArrayString_parsedAsStructure() {
            JsonState state = new JsonState();
            String jsonArray = "[{\"name\":\"ns1\",\"pods\":5},{\"name\":\"ns2\",\"pods\":3}]";
            state.put("namespaces", jsonArray);

            // JSON配列文字列はパースされ、構造化された配列として保存される
            assertEquals("ns1", state.getString("namespaces[0].name"));
            assertEquals(5, state.getInt("namespaces[0].pods", 0));
            assertEquals("ns2", state.getString("namespaces[1].name"));
            assertEquals(3, state.getInt("namespaces[1].pods", 0));
        }

        @Test
        @DisplayName("例11: JsonNodeの直接保存")
        void put_jsonNode_storesAsStructure() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree("{\"key\":\"value\"}");

            JsonState state = new JsonState();
            state.put("data", node);

            // JsonNodeは構造として保存される
            assertEquals("value", state.getString("data.key"));
        }

        @Test
        @DisplayName("例12: nullの保存")
        void put_null_storesNull() {
            JsonState state = new JsonState();
            state.put("value", null);

            // キーは存在するが値はnull
            assertNull(state.getString("value"));
        }
    }

    // ========================================================================
    // Getter Tests
    // ========================================================================

    @Nested
    @DisplayName("getString() / getInt() / etc - 値の取得")
    class GetterTests {

        @Test
        @DisplayName("getString: 存在するキー")
        void getString_existingKey_returnsValue() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            assertEquals("Mery", state.getString("name"));
        }

        @Test
        @DisplayName("getString: 数値を文字列として取得")
        void getString_number_convertsToString() {
            JsonState state = new JsonState();
            state.put("count", 42);

            assertEquals("42", state.getString("count"));
        }

        @Test
        @DisplayName("getString: 存在しないキー")
        void getString_missingKey_returnsNull() {
            JsonState state = new JsonState();

            assertNull(state.getString("nonexistent"));
            assertEquals("default", state.getString("nonexistent", "default"));
        }

        @Test
        @DisplayName("getInt: 整数値の取得")
        void getInt_integerValue_returnsInt() {
            JsonState state = new JsonState();
            state.put("count", 42);

            assertEquals(42, state.getInt("count", 0));
        }

        @Test
        @DisplayName("getInt: 文字列から整数への変換")
        void getInt_stringNumber_convertsToInt() {
            JsonState state = new JsonState();
            state.put("count", "42");

            assertEquals(42, state.getInt("count", 0));
        }

        @Test
        @DisplayName("getInt: 変換不可能な文字列")
        void getInt_invalidString_returnsDefault() {
            JsonState state = new JsonState();
            state.put("value", "not a number");

            assertEquals(-1, state.getInt("value", -1));
        }

        @Test
        @DisplayName("getLong: 大きな整数値")
        void getLong_bigNumber_returnsLong() {
            JsonState state = new JsonState();
            state.put("bigNumber", 9223372036854775807L);

            assertEquals(9223372036854775807L, state.getLong("bigNumber", 0L));
        }

        @Test
        @DisplayName("getDouble: 浮動小数点値")
        void getDouble_floatValue_returnsDouble() {
            JsonState state = new JsonState();
            state.put("price", 19.99);

            assertEquals(19.99, state.getDouble("price", 0.0), 0.001);
        }

        @Test
        @DisplayName("getBoolean: 真偽値の取得")
        void getBoolean_booleanValue_returnsBoolean() {
            JsonState state = new JsonState();
            state.put("enabled", true);
            state.put("debug", false);

            assertTrue(state.getBoolean("enabled", false));
            assertFalse(state.getBoolean("debug", true));
        }

        @Test
        @DisplayName("get: Optionalで値を取得")
        void get_existingKey_returnsPresent() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            Optional<JsonNode> opt = state.get("name");

            assertTrue(opt.isPresent());
            assertEquals("Mery", opt.get().asText());
        }

        @Test
        @DisplayName("get: 存在しないキーはempty")
        void get_missingKey_returnsEmpty() {
            JsonState state = new JsonState();

            Optional<JsonNode> opt = state.get("nonexistent");

            assertTrue(opt.isEmpty());
        }

        @Test
        @DisplayName("has: 値が存在する場合")
        void has_existingKey_returnsTrue() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("count", 0);
            state.put("enabled", false);

            assertTrue(state.has("name"));
            assertTrue(state.has("count"));  // 0も値として存在
            assertTrue(state.has("enabled")); // falseも値として存在
        }

        @Test
        @DisplayName("has: 値が存在しない場合")
        void has_missingKey_returnsFalse() {
            JsonState state = new JsonState();

            assertFalse(state.has("nonexistent"));
        }

        @Test
        @DisplayName("has: ネストしたパスの存在確認")
        void has_nestedPath_checksCorrectly() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");

            assertTrue(state.has("user"));
            assertTrue(state.has("user.profile"));
            assertTrue(state.has("user.profile.name"));
            assertFalse(state.has("user.profile.age"));
        }
    }

    // ========================================================================
    // remove() / clear() Tests
    // ========================================================================

    @Nested
    @DisplayName("remove() / clear() - 削除")
    class RemoveTests {

        @Test
        @DisplayName("remove: トップレベルキーの削除")
        void remove_topLevelKey_removesIt() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("age", 25);

            boolean removed = state.remove("name");

            assertTrue(removed);
            assertFalse(state.has("name"));
            assertTrue(state.has("age"));
        }

        @Test
        @DisplayName("remove: ネストしたキーの削除")
        void remove_nestedKey_removesIt() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");
            state.put("user.profile.age", 25);

            boolean removed = state.remove("user.profile.name");

            assertTrue(removed);
            assertFalse(state.has("user.profile.name"));
            assertTrue(state.has("user.profile.age"));
            assertTrue(state.has("user.profile"));
        }

        @Test
        @DisplayName("remove: 存在しないキーの削除")
        void remove_nonexistentKey_returnsFalse() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            boolean removed = state.remove("nonexistent");

            assertFalse(removed);
            assertEquals(1, state.size());
        }

        @Test
        @DisplayName("remove: 空パスの削除")
        void remove_emptyPath_returnsFalse() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            assertFalse(state.remove(""));
            assertFalse(state.remove(null));
            assertEquals(1, state.size());
        }

        @Test
        @DisplayName("clear: 全ての値をクリア")
        void clear_removesAllValues() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("user.profile.age", 25);
            state.put("hosts[0]", "server1");

            state.clear();

            assertTrue(state.isEmpty());
            assertEquals(0, state.size());
            assertFalse(state.has("name"));
        }

        @Test
        @DisplayName("clear: 空の状態でも例外なし")
        void clear_emptyState_noException() {
            JsonState state = new JsonState();

            assertDoesNotThrow(state::clear);
            assertTrue(state.isEmpty());
        }
    }

    // ========================================================================
    // Output Tests
    // ========================================================================

    @Nested
    @DisplayName("toString() / toStringOfJson() / toStringOfYaml() - 出力")
    class OutputTests {

        @Test
        @DisplayName("toString: コンパクトJSON出力")
        void toString_returnsCompactJson() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            String json = state.toString();

            assertTrue(json.contains("\"name\""));
            assertTrue(json.contains("\"Mery\""));
            assertFalse(json.contains("\n")); // コンパクト形式
        }

        @Test
        @DisplayName("toString: 空の状態")
        void toString_emptyState_returnsEmptyObject() {
            JsonState state = new JsonState();

            assertEquals("{}", state.toString());
        }

        @Test
        @DisplayName("toStringOfJson: 全体を出力")
        void toStringOfJson_emptyPath_returnsAll() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            String json1 = state.toStringOfJson("");
            String json2 = state.toStringOfJson(null);

            assertEquals(json1, json2);
            assertTrue(json1.contains("Mery"));
        }

        @Test
        @DisplayName("toStringOfJson: 特定パスを出力")
        void toStringOfJson_specificPath_returnsSubtree() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");
            state.put("user.profile.age", 25);
            state.put("other", "data");

            String json = state.toStringOfJson("user.profile");

            assertTrue(json.contains("Mery"));
            assertTrue(json.contains("25"));
            assertFalse(json.contains("other"));
        }

        @Test
        @DisplayName("toStringOfJson: 存在しないパス")
        void toStringOfJson_nonexistentPath_returnsNull() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            String json = state.toStringOfJson("nonexistent");

            assertEquals("null", json);
        }

        @Test
        @DisplayName("toStringOfYaml: 全体をYAML出力")
        void toStringOfYaml_emptyPath_returnsAll() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("age", 25);

            String yaml = state.toStringOfYaml("");

            assertTrue(yaml.contains("name:"));
            assertTrue(yaml.contains("Mery"));
            assertTrue(yaml.contains("age:"));
            assertTrue(yaml.contains("25"));
        }

        @Test
        @DisplayName("toStringOfYaml: ネストした構造")
        void toStringOfYaml_nestedStructure_outputsCorrectly() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");
            state.put("user.profile.age", 25);

            String yaml = state.toStringOfYaml("");

            assertTrue(yaml.contains("user:"));
            assertTrue(yaml.contains("profile:"));
            assertTrue(yaml.contains("name: Mery"));
        }

        @Test
        @DisplayName("toStringOfYaml: 配列構造")
        void toStringOfYaml_arrayStructure_outputsCorrectly() {
            JsonState state = new JsonState();
            state.put("hosts[0]", "server1");
            state.put("hosts[1]", "server2");

            String yaml = state.toStringOfYaml("");

            assertTrue(yaml.contains("hosts:"));
            assertTrue(yaml.contains("- server1"));
            assertTrue(yaml.contains("- server2"));
        }

        @Test
        @DisplayName("toStringOfYaml: 存在しないパス")
        void toStringOfYaml_nonexistentPath_returnsNull() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            String yaml = state.toStringOfYaml("nonexistent");

            assertEquals("null", yaml);
        }
    }

    // ========================================================================
    // Utility Tests
    // ========================================================================

    @Nested
    @DisplayName("isEmpty() / size() / copy() / fieldNames() - ユーティリティ")
    class UtilityTests {

        @Test
        @DisplayName("isEmpty: 空の状態")
        void isEmpty_emptyState_returnsTrue() {
            JsonState state = new JsonState();

            assertTrue(state.isEmpty());
        }

        @Test
        @DisplayName("isEmpty: 値がある状態")
        void isEmpty_withValues_returnsFalse() {
            JsonState state = new JsonState();
            state.put("name", "Mery");

            assertFalse(state.isEmpty());
        }

        @Test
        @DisplayName("size: トップレベルキーのカウント")
        void size_countsTopLevelKeys() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("age", 25);
            state.put("enabled", true);

            assertEquals(3, state.size());
        }

        @Test
        @DisplayName("size: ネストした値はカウントされない")
        void size_nestedValues_countAsOne() {
            JsonState state = new JsonState();
            state.put("user.profile.name", "Mery");
            state.put("user.profile.age", 25);

            assertEquals(1, state.size()); // "user"のみ
        }

        @Test
        @DisplayName("fieldNames: トップレベルキーの列挙")
        void fieldNames_returnsTopLevelKeys() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("age", 25);
            state.put("city", "Tokyo");

            List<String> keys = new ArrayList<>();
            state.fieldNames().forEachRemaining(keys::add);

            assertEquals(3, keys.size());
            assertTrue(keys.contains("name"));
            assertTrue(keys.contains("age"));
            assertTrue(keys.contains("city"));
        }

        @Test
        @DisplayName("copy: ディープコピー")
        void copy_createsIndependentCopy() {
            JsonState original = new JsonState();
            original.put("name", "Mery");
            original.put("user.age", 25);

            JsonState copied = original.copy();
            copied.put("name", "Alice");
            copied.put("user.age", 30);

            // 元のデータは変更されない
            assertEquals("Mery", original.getString("name"));
            assertEquals(25, original.getInt("user.age", 0));
            // コピーは変更される
            assertEquals("Alice", copied.getString("name"));
            assertEquals(30, copied.getInt("user.age", 0));
        }

        @Test
        @DisplayName("getRoot: ルートノードの直接操作")
        void getRoot_directManipulation_affectsState() {
            JsonState state = new JsonState();
            ObjectNode root = state.getRoot();
            root.put("direct", "value");

            assertEquals("value", state.getString("direct"));
        }

        @Test
        @DisplayName("コンストラクタ: ObjectNodeを指定")
        void constructor_withObjectNode_usesIt() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", "Mery");

            JsonState state = new JsonState(node);

            assertEquals("Mery", state.getString("name"));
        }

        @Test
        @DisplayName("コンストラクタ: nullを指定")
        void constructor_withNull_createsEmptyState() {
            JsonState state = new JsonState(null);

            assertTrue(state.isEmpty());
            assertNotNull(state.getRoot());
        }
    }

    // ========================================================================
    // Cross-Method Consistency Tests
    // ========================================================================

    @Nested
    @DisplayName("メソッド間の整合性テスト")
    class ConsistencyTests {

        @Test
        @DisplayName("isEmpty と size の整合性")
        void isEmpty_size_consistency() {
            JsonState state = new JsonState();

            // 空の状態
            assertEquals(state.isEmpty(), state.size() == 0);

            state.put("name", "Mery");

            // 値がある状態
            assertEquals(state.isEmpty(), state.size() == 0);
        }

        @Test
        @DisplayName("put と get の整合性")
        void put_get_consistency() {
            JsonState state = new JsonState();

            state.put("string", "value");
            state.put("int", 42);
            state.put("long", 9999999999L);
            state.put("double", 3.14);
            state.put("boolean", true);

            assertEquals("value", state.getString("string"));
            assertEquals(42, state.getInt("int", 0));
            assertEquals(9999999999L, state.getLong("long", 0L));
            assertEquals(3.14, state.getDouble("double", 0.0), 0.001);
            assertTrue(state.getBoolean("boolean", false));
        }

        @Test
        @DisplayName("put と has の整合性")
        void put_has_consistency() {
            JsonState state = new JsonState();

            assertFalse(state.has("key"));

            state.put("key", "value");

            assertTrue(state.has("key"));

            state.remove("key");

            assertFalse(state.has("key"));
        }

        @Test
        @DisplayName("copy と toString の整合性")
        void copy_toString_consistency() {
            JsonState original = new JsonState();
            original.put("name", "Mery");
            original.put("user.age", 25);

            JsonState copied = original.copy();

            assertEquals(original.toString(), copied.toString());
        }

        @Test
        @DisplayName("select と getter の整合性")
        void select_getter_consistency() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("count", 42);

            // selectとgetStringは同じ値を返す
            assertEquals(state.select("name").asText(), state.getString("name"));
            assertEquals(state.select("count").asInt(), state.getInt("count", 0));
        }

        @Test
        @DisplayName("toStringOfJson と toStringOfYaml の整合性")
        void toStringOfJson_toStringOfYaml_sameData() {
            JsonState state = new JsonState();
            state.put("name", "Mery");
            state.put("count", 42);

            String json = state.toStringOfJson("");
            String yaml = state.toStringOfYaml("");

            // 両方に同じデータが含まれる
            assertTrue(json.contains("Mery"));
            assertTrue(yaml.contains("Mery"));
            assertTrue(json.contains("42"));
            assertTrue(yaml.contains("42"));
        }
    }
}
