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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for variable expansion (${...}) functionality.
 *
 * Based on specification: 106_VariableExpansion_260129_oo01
 */
class VariableExpansionSpecTest {

    private ActorRef<Object> actor;

    @BeforeEach
    void setUp() {
        actor = new ActorRef<>("test", new Object());
    }

    // ========================================================================
    // Basic Variable Expansion Tests
    // ========================================================================

    @Nested
    @DisplayName("基本的な変数展開")
    class BasicExpansionTests {

        @Test
        @DisplayName("例1: 文字列値の展開")
        void expand_stringValue() {
            actor.json().put("name", "Mery");

            String result = actor.expandVariables("Hello, ${name}!");

            assertEquals("Hello, Mery!", result);
        }

        @Test
        @DisplayName("例2: 数値の展開")
        void expand_numericValue() {
            actor.json().put("count", 42);

            String result = actor.expandVariables("Count: ${count}");

            assertEquals("Count: 42", result);
        }

        @Test
        @DisplayName("例3: 存在しない変数は展開されない")
        void expand_nonexistentVariable_unchanged() {
            String result = actor.expandVariables("Value: ${nonexistent}");

            assertEquals("Value: ${nonexistent}", result);
        }

        @Test
        @DisplayName("変数を含まない文字列はそのまま")
        void expand_noVariables_unchanged() {
            String result = actor.expandVariables("Hello, World!");

            assertEquals("Hello, World!", result);
        }

        @Test
        @DisplayName("null入力はnullを返す")
        void expand_nullInput_returnsNull() {
            String result = actor.expandVariables(null);

            assertNull(result);
        }

        @Test
        @DisplayName("空文字列はそのまま")
        void expand_emptyString_returnsEmpty() {
            String result = actor.expandVariables("");

            assertEquals("", result);
        }
    }

    // ========================================================================
    // ${result} Tests
    // ========================================================================

    @Nested
    @DisplayName("${result} の展開")
    class ResultExpansionTests {

        @Test
        @DisplayName("例4: プレーンテキスト結果の展開")
        void expand_plainTextResult() {
            actor.setLastResult(new ActionResult(true, "hello world"));

            String result = actor.expandVariables("Output: ${result}");

            assertEquals("Output: hello world", result);
        }

        @Test
        @DisplayName("例5: JSONオブジェクト結果の展開【核心】")
        void expand_jsonObjectResult_returnsJsonString() {
            String jsonOutput = "{\"cluster\":\"https://k8s.example.com\",\"nodes\":2}";
            actor.setLastResult(new ActionResult(true, jsonOutput));

            String result = actor.expandVariables("${result}");

            // JSON文字列がそのまま返されるべき
            assertTrue(result.contains("cluster"));
            assertTrue(result.contains("https://k8s.example.com"));
            assertTrue(result.contains("nodes"));
            // パースして検証
            assertTrue(result.startsWith("{") && result.endsWith("}"));
        }

        @Test
        @DisplayName("例6: JSON配列結果の展開")
        void expand_jsonArrayResult_returnsJsonString() {
            String jsonArray = "[{\"name\":\"ns1\"},{\"name\":\"ns2\"}]";
            actor.setLastResult(new ActionResult(true, jsonArray));

            String result = actor.expandVariables("${result}");

            assertTrue(result.startsWith("[") && result.endsWith("]"));
            assertTrue(result.contains("ns1"));
            assertTrue(result.contains("ns2"));
        }

        @Test
        @DisplayName("例7: ${result}を含む複合文字列")
        void expand_resultInComplexString() {
            actor.setLastResult(new ActionResult(true, "{\"key\":\"value\"}"));

            String result = actor.expandVariables("Data: ${result} (end)");

            assertTrue(result.startsWith("Data: "));
            assertTrue(result.endsWith(" (end)"));
            assertTrue(result.contains("key"));
            assertTrue(result.contains("value"));
        }

        @Test
        @DisplayName("例16: null結果は展開されない")
        void expand_nullResult_unchanged() {
            actor.setLastResult(new ActionResult(true, null));

            String result = actor.expandVariables("${result}");

            assertEquals("${result}", result);
        }
    }

    // ========================================================================
    // Nested Path Tests
    // ========================================================================

    @Nested
    @DisplayName("ネストしたパスの展開")
    class NestedPathTests {

        @Test
        @DisplayName("例8: ネストしたオブジェクトからの値取得")
        void expand_nestedPath() {
            actor.json().put("user.profile.name", "Mery");

            String result = actor.expandVariables("Name: ${user.profile.name}");

            assertEquals("Name: Mery", result);
        }

        @Test
        @DisplayName("例9: 配列要素からの値取得")
        void expand_arrayElements() {
            actor.json().put("hosts[0]", "server1");
            actor.json().put("hosts[1]", "server2");

            String result = actor.expandVariables("First: ${hosts[0]}, Second: ${hosts[1]}");

            assertEquals("First: server1, Second: server2", result);
        }

        @Test
        @DisplayName("深いネスト")
        void expand_deepNesting() {
            actor.json().put("a.b.c.d.e", "deep");

            String result = actor.expandVariables("Value: ${a.b.c.d.e}");

            assertEquals("Value: deep", result);
        }
    }

    // ========================================================================
    // Object/Array Expansion Tests
    // ========================================================================

    @Nested
    @DisplayName("オブジェクト/配列の展開")
    class ObjectArrayExpansionTests {

        @Test
        @DisplayName("例10: オブジェクト値の展開")
        void expand_objectValue_returnsJsonString() {
            actor.json().put("data", "{\"key\":\"value\",\"num\":42}");

            String result = actor.expandVariables("${data}");

            // オブジェクトはJSON文字列として展開されるべき
            assertTrue(result.contains("key"));
            assertTrue(result.contains("value"));
            assertTrue(result.contains("num"));
            assertTrue(result.contains("42"));
        }

        @Test
        @DisplayName("例11: 配列値の展開")
        void expand_arrayValue_returnsJsonString() {
            actor.json().put("items", "[1,2,3]");

            String result = actor.expandVariables("${items}");

            assertTrue(result.contains("1"));
            assertTrue(result.contains("2"));
            assertTrue(result.contains("3"));
        }

        @Test
        @DisplayName("例12: ネストしたオブジェクトの部分展開 - スカラー値")
        void expand_nestedObjectPartial_scalarValue() {
            actor.json().put("cluster", "{\"name\":\"prod\",\"nodes\":[\"n1\",\"n2\"]}");

            String name = actor.expandVariables("${cluster.name}");

            assertEquals("prod", name);
        }

        @Test
        @DisplayName("例12: ネストしたオブジェクトの部分展開 - 配列値")
        void expand_nestedObjectPartial_arrayValue() {
            actor.json().put("cluster", "{\"name\":\"prod\",\"nodes\":[\"n1\",\"n2\"]}");

            String nodes = actor.expandVariables("${cluster.nodes}");

            assertTrue(nodes.contains("n1"));
            assertTrue(nodes.contains("n2"));
        }

        @Test
        @DisplayName("例15: 空のJSONオブジェクト")
        void expand_emptyJsonObject() {
            actor.setLastResult(new ActionResult(true, "{}"));

            String result = actor.expandVariables("${result}");

            assertEquals("{}", result);
        }

        @Test
        @DisplayName("空のJSON配列")
        void expand_emptyJsonArray() {
            actor.setLastResult(new ActionResult(true, "[]"));

            String result = actor.expandVariables("${result}");

            assertEquals("[]", result);
        }
    }

    // ========================================================================
    // Workflow Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("ワークフロー統合シナリオ")
    class WorkflowIntegrationTests {

        @Test
        @DisplayName("例13: executeCommand → putJson パターン")
        void workflow_executeCommandToPutJson() {
            // 1. executeCommandがJSONを出力したことをシミュレート
            String commandOutput = "{\"total\":2}";
            actor.setLastResult(new ActionResult(true, commandOutput));

            // 2. ${result}を展開
            String expandedValue = actor.expandVariables("${result}");

            // 3. putJsonでクラスターに保存
            actor.putJson("cluster", expandedValue);

            // 4. 構造化データとしてアクセスできることを確認
            assertEquals("2", actor.json().getString("cluster.total"));
        }

        @Test
        @DisplayName("例14: 複数回のputJsonパターン")
        void workflow_multiplePutJson() {
            // 1回目のコマンド実行
            actor.setLastResult(new ActionResult(true, "{\"name\":\"cluster1\"}"));
            actor.putJson("cluster", actor.expandVariables("${result}"));

            // 2回目のコマンド実行
            actor.setLastResult(new ActionResult(true, "[{\"ns\":\"default\"}]"));
            actor.putJson("namespaces", actor.expandVariables("${result}"));

            // 検証
            assertEquals("cluster1", actor.json().getString("cluster.name"));
            assertEquals("default", actor.json().getString("namespaces[0].ns"));
        }

        @Test
        @DisplayName("kubectl出力のシミュレーション")
        void workflow_kubectlOutputSimulation() {
            // kubectlの出力をシミュレート
            String kubectlOutput = "{\"cluster\":\"https://192.168.5.23:16443\",\"hostname\":\"stonefly514\",\"total\":2,\"byStatus\":{\"NotReady\":1,\"Ready\":1},\"names\":[\"stonefly522\",\"stonefly523\"]}";
            actor.setLastResult(new ActionResult(true, kubectlOutput));

            // ${result}を展開してputJson
            String expanded = actor.expandVariables("${result}");
            actor.putJson("cluster", expanded);

            // 構造化データとしてアクセス
            assertEquals("https://192.168.5.23:16443", actor.json().getString("cluster.cluster"));
            assertEquals("stonefly514", actor.json().getString("cluster.hostname"));
            assertEquals(2, actor.json().getInt("cluster.total", 0));
            assertEquals(1, actor.json().getInt("cluster.byStatus.NotReady", 0));
            assertEquals(1, actor.json().getInt("cluster.byStatus.Ready", 0));
            assertEquals("stonefly522", actor.json().getString("cluster.names[0]"));
            assertEquals("stonefly523", actor.json().getString("cluster.names[1]"));
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("エッジケース")
    class EdgeCaseTests {

        @Test
        @DisplayName("例17: 複数の変数展開")
        void expand_multipleVariables() {
            actor.json().put("a", "1");
            actor.json().put("b", "2");

            String result = actor.expandVariables("${a} + ${b} = 3");

            assertEquals("1 + 2 = 3", result);
        }

        @Test
        @DisplayName("例18: json.プレフィックス")
        void expand_jsonPrefix() {
            actor.json().put("value", "test");

            String result1 = actor.expandVariables("${value}");
            String result2 = actor.expandVariables("${json.value}");

            assertEquals("test", result1);
            assertEquals("test", result2);
        }

        @Test
        @DisplayName("不完全な変数構文 - 開き括弧のみ")
        void expand_incompleteVariable_onlyOpen() {
            String result = actor.expandVariables("Value: ${incomplete");

            assertEquals("Value: ${incomplete", result);
        }

        @Test
        @DisplayName("不完全な変数構文 - 閉じ括弧のみ")
        void expand_incompleteVariable_onlyClose() {
            String result = actor.expandVariables("Value: incomplete}");

            assertEquals("Value: incomplete}", result);
        }

        @Test
        @DisplayName("ネストした${...}パターン - 展開されない")
        void expand_nestedDollarBrace() {
            actor.json().put("outer", "value");

            // 内側の${が先に検出されるため、${outer}が展開される
            // 結果として${value}になるが、現在の実装では1パスなので${${outer}}のまま
            String result = actor.expandVariables("${${outer}}");

            // 現在の実装ではネストは展開されない（複雑なパースが必要）
            assertEquals("${${outer}}", result);
        }

        @Test
        @DisplayName("Boolean値の展開")
        void expand_booleanValue() {
            actor.json().put("enabled", true);
            actor.json().put("disabled", false);

            assertEquals("true", actor.expandVariables("${enabled}"));
            assertEquals("false", actor.expandVariables("${disabled}"));
        }

        @Test
        @DisplayName("特殊文字を含む値の展開")
        void expand_specialCharacters() {
            actor.json().put("path", "/usr/local/bin");
            actor.json().put("url", "https://example.com?a=1&b=2");

            assertEquals("/usr/local/bin", actor.expandVariables("${path}"));
            assertEquals("https://example.com?a=1&b=2", actor.expandVariables("${url}"));
        }

        @Test
        @DisplayName("改行を含む値の展開")
        void expand_multilineValue() {
            actor.json().put("text", "line1\nline2\nline3");

            String result = actor.expandVariables("${text}");

            assertTrue(result.contains("\n"));
            assertTrue(result.contains("line1"));
            assertTrue(result.contains("line2"));
        }
    }

    // ========================================================================
    // Current Behavior Tests (to be fixed)
    // ========================================================================

    @Nested
    @DisplayName("現在の問題点（修正対象）")
    class CurrentBehaviorIssuesTests {

        @Test
        @DisplayName("問題: JSONオブジェクトのresultが正しく展開される")
        void issue_jsonObjectResultExpansion() {
            // この動作が正しく実装されていることを確認
            String jsonOutput = "{\"key\":\"value\"}";
            actor.setLastResult(new ActionResult(true, jsonOutput));

            String result = actor.expandVariables("${result}");

            // 空文字ではなく、JSON文字列が返されるべき
            assertFalse(result.isEmpty(), "Result should not be empty");
            assertTrue(result.contains("key"), "Result should contain 'key'");
            assertTrue(result.contains("value"), "Result should contain 'value'");
        }

        @Test
        @DisplayName("問題: putされたJSONオブジェクトが正しく展開される")
        void issue_putJsonObjectExpansion() {
            actor.json().put("data", "{\"cluster\":\"prod\"}");

            String result = actor.expandVariables("${data}");

            assertFalse(result.isEmpty(), "Result should not be empty");
            assertTrue(result.contains("cluster"), "Result should contain 'cluster'");
            assertTrue(result.contains("prod"), "Result should contain 'prod'");
        }
    }
}
