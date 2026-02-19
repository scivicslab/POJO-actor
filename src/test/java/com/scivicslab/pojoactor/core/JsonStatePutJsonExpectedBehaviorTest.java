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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EXPECTED behavior of putJson with JSON strings.
 *
 * These tests verify that JSON strings passed to putJson should be
 * automatically parsed and stored as structured objects, not as
 * literal strings.
 *
 * Based on specification: 100_JsonStatePutJson_260129_oo01
 *
 * NOTE: These tests will FAIL with the current implementation.
 * They document the expected behavior that needs to be implemented.
 */
class JsonStatePutJsonExpectedBehaviorTest {

    // ========================================================================
    // JSON Object String Tests
    // ========================================================================

    @Test
    @DisplayName("例6: JSON文字列（オブジェクト）は構造として保存されるべき")
    void put_jsonObjectString_shouldBeStoredAsStructure() {
        JsonState state = new JsonState();
        String jsonString = "{\"cluster\":\"https://k8s.example.com\",\"nodes\":2}";
        state.put("data", jsonString);

        // JSON文字列はパースされ、構造化されたオブジェクトとして保存される
        // ネストしたパスでアクセスできるべき
        assertEquals("https://k8s.example.com", state.getString("data.cluster"));
        assertEquals(2, state.getInt("data.nodes", 0));
    }

    @Test
    @DisplayName("例6b: JSON文字列保存後のYAML出力は構造化されるべき")
    void put_jsonObjectString_yamlShouldBeStructured() {
        JsonState state = new JsonState();
        String jsonString = "{\"cluster\":\"https://k8s.example.com\",\"nodes\":2}";
        state.put("data", jsonString);

        String yaml = state.toStringOfYaml("");

        // YAML出力は構造化されるべき（クォートされた文字列ではない）
        assertTrue(yaml.contains("data:"));
        assertTrue(yaml.contains("cluster: https://k8s.example.com") ||
                   yaml.contains("cluster: 'https://k8s.example.com'"));
        assertTrue(yaml.contains("nodes: 2"));

        // 単なるJSON文字列としてクォートされていてはいけない
        assertFalse(yaml.contains("'{\"cluster\""));
    }

    // ========================================================================
    // JSON Array String Tests
    // ========================================================================

    @Test
    @DisplayName("例7: JSON文字列（配列）は構造として保存されるべき")
    void put_jsonArrayString_shouldBeStoredAsStructure() {
        JsonState state = new JsonState();
        String jsonArray = "[{\"name\":\"ns1\",\"pods\":5},{\"name\":\"ns2\",\"pods\":3}]";
        state.put("namespaces", jsonArray);

        // 配列要素にパスでアクセスできるべき
        assertEquals("ns1", state.getString("namespaces[0].name"));
        assertEquals(5, state.getInt("namespaces[0].pods", 0));
        assertEquals("ns2", state.getString("namespaces[1].name"));
        assertEquals(3, state.getInt("namespaces[1].pods", 0));
    }

    @Test
    @DisplayName("例7b: JSON配列保存後のYAML出力は構造化されるべき")
    void put_jsonArrayString_yamlShouldBeStructured() {
        JsonState state = new JsonState();
        String jsonArray = "[{\"name\":\"ns1\",\"pods\":5},{\"name\":\"ns2\",\"pods\":3}]";
        state.put("namespaces", jsonArray);

        String yaml = state.toStringOfYaml("");

        // YAML出力は配列として構造化されるべき
        assertTrue(yaml.contains("namespaces:"));
        assertTrue(yaml.contains("- name: ns1") || yaml.contains("-\n  name: ns1"));
        assertTrue(yaml.contains("pods: 5"));
        assertTrue(yaml.contains("- name: ns2") || yaml.contains("-\n  name: ns2"));
        assertTrue(yaml.contains("pods: 3"));
    }

    // ========================================================================
    // Workflow ${result} Pattern Tests
    // ========================================================================

    @Test
    @DisplayName("例8: ワークフロー${result}パターン - クラスター情報")
    void put_workflowResultPattern_clusterInfo() {
        JsonState state = new JsonState();

        // kubectlの出力をシミュレート
        String kubectlOutput = "{\"cluster\":\"https://192.168.5.23:16443\",\"hostname\":\"stonefly514\",\"total\":2,\"byStatus\":{\"NotReady\":1,\"Ready\":1},\"names\":[\"stonefly522\",\"stonefly523\"]}";
        state.put("cluster", kubectlOutput);

        // 構造化されたデータとしてアクセスできるべき
        assertEquals("https://192.168.5.23:16443", state.getString("cluster.cluster"));
        assertEquals("stonefly514", state.getString("cluster.hostname"));
        assertEquals(2, state.getInt("cluster.total", 0));
        assertEquals(1, state.getInt("cluster.byStatus.NotReady", 0));
        assertEquals(1, state.getInt("cluster.byStatus.Ready", 0));
        assertEquals("stonefly522", state.getString("cluster.names[0]"));
        assertEquals("stonefly523", state.getString("cluster.names[1]"));
    }

    @Test
    @DisplayName("例8b: ワークフロー${result}パターン - namespace情報")
    void put_workflowResultPattern_namespaceInfo() {
        JsonState state = new JsonState();

        // kubectlの出力をシミュレート（複数namespace）
        String kubectlOutput = "[{\"namespace\":\"default\",\"pods\":{\"total\":0,\"byPhase\":{}}},{\"namespace\":\"kube-system\",\"pods\":{\"total\":7,\"byPhase\":{\"Running\":7}}}]";
        state.put("namespaces", kubectlOutput);

        // 構造化されたデータとしてアクセスできるべき
        assertEquals("default", state.getString("namespaces[0].namespace"));
        assertEquals(0, state.getInt("namespaces[0].pods.total", -1));
        assertEquals("kube-system", state.getString("namespaces[1].namespace"));
        assertEquals(7, state.getInt("namespaces[1].pods.total", -1));
        assertEquals(7, state.getInt("namespaces[1].pods.byPhase.Running", -1));
    }

    @Test
    @DisplayName("例8c: 全体のYAML出力が人間に読みやすい形式")
    void put_workflowResultPattern_readableYamlOutput() {
        JsonState state = new JsonState();

        state.put("cluster", "{\"name\":\"production\",\"nodes\":2}");
        state.put("namespaces", "[{\"name\":\"default\",\"pods\":0},{\"name\":\"kube-system\",\"pods\":7}]");

        String yaml = state.toStringOfYaml("");

        // 人間が読みやすいYAML形式であるべき
        // cluster:
        //   name: production
        //   nodes: 2
        // namespaces:
        // - name: default
        //   pods: 0
        // - name: kube-system
        //   pods: 7

        assertTrue(yaml.contains("cluster:"));
        assertTrue(yaml.contains("name: production"));
        assertTrue(yaml.contains("nodes: 2"));
        assertTrue(yaml.contains("namespaces:"));

        // JSON文字列がそのまま出力されていてはいけない
        assertFalse(yaml.contains("{\"name\":\"production\""));
        assertFalse(yaml.contains("[{\"name\":\"default\""));
    }

    // ========================================================================
    // Non-JSON String Tests (Should NOT be parsed)
    // ========================================================================

    @Test
    @DisplayName("例9: 非JSON文字列はそのまま保存されるべき")
    void put_nonJsonString_shouldBeStoredAsIs() {
        JsonState state = new JsonState();
        state.put("message", "Hello, World!");
        state.put("path", "/usr/local/bin");

        // 通常の文字列はそのまま保存される
        assertEquals("Hello, World!", state.getString("message"));
        assertEquals("/usr/local/bin", state.getString("path"));
    }

    @Test
    @DisplayName("例10: 無効なJSON文字列はそのまま保存されるべき")
    void put_invalidJsonString_shouldBeStoredAsIs() {
        JsonState state = new JsonState();
        state.put("invalid", "{not valid json}");

        // パースに失敗した場合は文字列として保存される
        assertEquals("{not valid json}", state.getString("invalid"));

        // 例外は発生しない
        assertDoesNotThrow(() -> state.put("another", "{broken: json"));
    }

    @Test
    @DisplayName("例10b: JSONらしくない文字列はパースを試みない")
    void put_nonJsonLookingString_shouldNotAttemptParse() {
        JsonState state = new JsonState();

        // {で始まらない文字列
        state.put("text1", "Hello {world}");
        state.put("text2", "array = [1, 2, 3]");
        state.put("text3", "  spaces before {json}");

        assertEquals("Hello {world}", state.getString("text1"));
        assertEquals("array = [1, 2, 3]", state.getString("text2"));
        assertEquals("  spaces before {json}", state.getString("text3"));
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    @DisplayName("空のJSONオブジェクトは構造として保存されるべき")
    void put_emptyJsonObject_shouldBeStoredAsStructure() {
        JsonState state = new JsonState();
        state.put("empty", "{}");

        // 空のオブジェクトとして保存される
        assertTrue(state.has("empty"));
        assertTrue(state.select("empty").isObject());
        assertEquals(0, state.select("empty").size());
    }

    @Test
    @DisplayName("空のJSON配列は構造として保存されるべき")
    void put_emptyJsonArray_shouldBeStoredAsStructure() {
        JsonState state = new JsonState();
        state.put("empty", "[]");

        // 空の配列として保存される
        assertTrue(state.has("empty"));
        assertTrue(state.select("empty").isArray());
        assertEquals(0, state.select("empty").size());
    }

    @Test
    @DisplayName("ネストしたJSON文字列も正しくパースされるべき")
    void put_deeplyNestedJson_shouldBeStoredAsStructure() {
        JsonState state = new JsonState();
        String deepJson = "{\"level1\":{\"level2\":{\"level3\":{\"value\":\"deep\"}}}}";
        state.put("data", deepJson);

        assertEquals("deep", state.getString("data.level1.level2.level3.value"));
    }

    @Test
    @DisplayName("JSON文字列内の特殊文字が正しく処理されるべき")
    void put_jsonWithSpecialChars_shouldBeStoredCorrectly() {
        JsonState state = new JsonState();
        String jsonWithSpecial = "{\"message\":\"Hello\\nWorld\",\"path\":\"C:\\\\Users\"}";
        state.put("data", jsonWithSpecial);

        assertEquals("Hello\nWorld", state.getString("data.message"));
        assertEquals("C:\\Users", state.getString("data.path"));
    }
}
