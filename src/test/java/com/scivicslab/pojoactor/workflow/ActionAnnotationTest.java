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

package com.scivicslab.pojoactor.workflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;

/**
 * Tests for @Action annotation and IIActorRef annotation discovery.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>@Action annotation is retained at runtime</li>
 *   <li>IIActorRef discovers @Action methods on wrapped objects</li>
 *   <li>@Action methods can be invoked via callByActionName</li>
 *   <li>Interface default methods with @Action work correctly (mixin pattern)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
@DisplayName("@Action Annotation Specification")
public class ActionAnnotationTest {

    // ========================================================================
    // Test fixtures: Sample classes with @Action annotations
    // ========================================================================

    /**
     * Simple POJO with @Action annotated methods.
     */
    static class SimpleCalculator {

        @Action("add")
        public ActionResult add(String args) {
            String[] parts = args.replace("[", "").replace("]", "").replace("\"", "").split(",");
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            return new ActionResult(true, String.valueOf(a + b));
        }

        @Action("multiply")
        public ActionResult multiply(String args) {
            String[] parts = args.replace("[", "").replace("]", "").replace("\"", "").split(",");
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            return new ActionResult(true, String.valueOf(a * b));
        }

        @Action("greet")
        public ActionResult greet(String args) {
            String name = args.replace("[", "").replace("]", "").replace("\"", "").trim();
            return new ActionResult(true, "Hello, " + name + "!");
        }

        // Method without @Action - should NOT be discoverable
        public ActionResult notAnAction(String args) {
            return new ActionResult(true, "This should not be called");
        }
    }

    /**
     * Interface with @Action default methods (mixin pattern).
     */
    interface Greetable {
        @Action("sayHello")
        default ActionResult sayHello(String args) {
            return new ActionResult(true, "Hello from interface default method!");
        }

        @Action("sayGoodbye")
        default ActionResult sayGoodbye(String args) {
            String name = args.replace("[", "").replace("]", "").replace("\"", "").trim();
            return new ActionResult(true, "Goodbye, " + name + "!");
        }
    }

    /**
     * Class implementing the mixin interface.
     */
    static class GreetableCalculator extends SimpleCalculator implements Greetable {
        // Inherits @Action methods from both SimpleCalculator and Greetable interface
    }

    /**
     * Class with method that has wrong return type (should be skipped).
     */
    static class InvalidActionClass {
        @Action("wrongReturnType")
        public String wrongReturnType(String args) {
            return "This has wrong return type";
        }

        @Action("validAction")
        public ActionResult validAction(String args) {
            return new ActionResult(true, "valid");
        }
    }

    /**
     * Class with method that has wrong parameter count (should be skipped).
     */
    static class WrongParamsClass {
        @Action("noParams")
        public ActionResult noParams() {
            return new ActionResult(true, "no params");
        }

        @Action("twoParams")
        public ActionResult twoParams(String a, String b) {
            return new ActionResult(true, "two params");
        }

        @Action("validAction")
        public ActionResult validAction(String args) {
            return new ActionResult(true, "valid");
        }
    }

    /**
     * Concrete IIActorRef for testing.
     */
    static class TestIIActorRef<T> extends IIActorRef<T> {
        public TestIIActorRef(String name, T object) {
            super(name, object);
        }
    }

    // ========================================================================
    // Tests for @Action annotation
    // ========================================================================

    @Nested
    @DisplayName("@Action Annotation Basics")
    class AnnotationBasics {

        @Test
        @DisplayName("@Action annotation should be retained at runtime")
        void annotationShouldBeRetainedAtRuntime() throws NoSuchMethodException {
            var method = SimpleCalculator.class.getMethod("add", String.class);
            Action action = method.getAnnotation(Action.class);

            assertNotNull(action, "@Action annotation should be present");
            assertEquals("add", action.value(), "Action name should be 'add'");
        }

        @Test
        @DisplayName("Method without @Action should not have annotation")
        void methodWithoutAnnotation() throws NoSuchMethodException {
            var method = SimpleCalculator.class.getMethod("notAnAction", String.class);
            Action action = method.getAnnotation(Action.class);

            assertNull(action, "Method without @Action should not have annotation");
        }

        @Test
        @DisplayName("Interface default methods can have @Action annotation")
        void interfaceDefaultMethodsCanHaveAnnotation() throws NoSuchMethodException {
            var method = Greetable.class.getMethod("sayHello", String.class);
            Action action = method.getAnnotation(Action.class);

            assertNotNull(action, "@Action annotation should be present on interface default method");
            assertEquals("sayHello", action.value());
        }
    }

    // ========================================================================
    // Tests for IIActorRef annotation discovery
    // ========================================================================

    @Nested
    @DisplayName("IIActorRef Annotation Discovery")
    class AnnotationDiscovery {

        @Test
        @DisplayName("Should discover and invoke @Action method")
        void shouldDiscoverAndInvokeActionMethod() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            ActionResult result = actorRef.callByActionName("add", "[5, 3]");

            assertTrue(result.isSuccess(), "Action should succeed");
            assertEquals("8", result.getResult(), "5 + 3 should equal 8");
        }

        @Test
        @DisplayName("Should discover multiple @Action methods")
        void shouldDiscoverMultipleActionMethods() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            ActionResult addResult = actorRef.callByActionName("add", "[10, 5]");
            ActionResult multiplyResult = actorRef.callByActionName("multiply", "[4, 7]");
            ActionResult greetResult = actorRef.callByActionName("greet", "[\"World\"]");

            assertTrue(addResult.isSuccess());
            assertEquals("15", addResult.getResult());

            assertTrue(multiplyResult.isSuccess());
            assertEquals("28", multiplyResult.getResult());

            assertTrue(greetResult.isSuccess());
            assertEquals("Hello, World!", greetResult.getResult());
        }

        @Test
        @DisplayName("Should NOT discover method without @Action")
        void shouldNotDiscoverMethodWithoutAnnotation() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            ActionResult result = actorRef.callByActionName("notAnAction", "[]");

            assertFalse(result.isSuccess(), "Method without @Action should not be discoverable");
            assertTrue(result.getResult().contains("Unknown action"));
        }

        @Test
        @DisplayName("Should return failure for unknown action")
        void shouldReturnFailureForUnknownAction() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            ActionResult result = actorRef.callByActionName("unknownAction", "[]");

            assertFalse(result.isSuccess());
            assertTrue(result.getResult().contains("Unknown action"));
        }
    }

    // ========================================================================
    // Tests for mixin pattern (interface default methods)
    // ========================================================================

    @Nested
    @DisplayName("Mixin Pattern with Interface Default Methods")
    class MixinPattern {

        @Test
        @DisplayName("Should discover @Action from interface default method")
        void shouldDiscoverActionFromInterfaceDefaultMethod() {
            GreetableCalculator calculator = new GreetableCalculator();
            TestIIActorRef<GreetableCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            ActionResult result = actorRef.callByActionName("sayHello", "[]");

            assertTrue(result.isSuccess());
            assertEquals("Hello from interface default method!", result.getResult());
        }

        @Test
        @DisplayName("Should discover @Action from both class and interface")
        void shouldDiscoverActionFromBothClassAndInterface() {
            GreetableCalculator calculator = new GreetableCalculator();
            TestIIActorRef<GreetableCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            // From SimpleCalculator class
            ActionResult addResult = actorRef.callByActionName("add", "[3, 4]");
            assertTrue(addResult.isSuccess());
            assertEquals("7", addResult.getResult());

            // From Greetable interface
            ActionResult helloResult = actorRef.callByActionName("sayHello", "[]");
            assertTrue(helloResult.isSuccess());
            assertEquals("Hello from interface default method!", helloResult.getResult());

            ActionResult goodbyeResult = actorRef.callByActionName("sayGoodbye", "[\"Alice\"]");
            assertTrue(goodbyeResult.isSuccess());
            assertEquals("Goodbye, Alice!", goodbyeResult.getResult());
        }

        @Test
        @DisplayName("Interface default method should receive arguments correctly")
        void interfaceDefaultMethodShouldReceiveArguments() {
            GreetableCalculator calculator = new GreetableCalculator();
            TestIIActorRef<GreetableCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            ActionResult result = actorRef.callByActionName("sayGoodbye", "[\"Bob\"]");

            assertTrue(result.isSuccess());
            assertEquals("Goodbye, Bob!", result.getResult());
        }
    }

    // ========================================================================
    // Tests for validation of @Action method signature
    // ========================================================================

    @Nested
    @DisplayName("@Action Method Signature Validation")
    class SignatureValidation {

        @Test
        @DisplayName("Should skip @Action method with wrong return type")
        void shouldSkipMethodWithWrongReturnType() {
            InvalidActionClass obj = new InvalidActionClass();
            TestIIActorRef<InvalidActionClass> actorRef = new TestIIActorRef<>("invalid", obj);

            // wrongReturnType has @Action but returns String instead of ActionResult
            ActionResult wrongResult = actorRef.callByActionName("wrongReturnType", "[]");
            assertFalse(wrongResult.isSuccess(), "Method with wrong return type should not be discovered");

            // validAction should still work
            ActionResult validResult = actorRef.callByActionName("validAction", "[]");
            assertTrue(validResult.isSuccess());
            assertEquals("valid", validResult.getResult());
        }

        @Test
        @DisplayName("Should skip @Action method with wrong parameter count")
        void shouldSkipMethodWithWrongParameterCount() {
            WrongParamsClass obj = new WrongParamsClass();
            TestIIActorRef<WrongParamsClass> actorRef = new TestIIActorRef<>("wrong", obj);

            // noParams has @Action but takes no parameters
            ActionResult noParamsResult = actorRef.callByActionName("noParams", "[]");
            assertFalse(noParamsResult.isSuccess(), "Method with no params should not be discovered");

            // twoParams has @Action but takes two parameters
            ActionResult twoParamsResult = actorRef.callByActionName("twoParams", "[]");
            assertFalse(twoParamsResult.isSuccess(), "Method with two params should not be discovered");

            // validAction should still work
            ActionResult validResult = actorRef.callByActionName("validAction", "[]");
            assertTrue(validResult.isSuccess());
        }
    }

    // ========================================================================
    // Tests for error handling
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        /**
         * Class with @Action that throws exception.
         */
        static class ThrowingClass {
            @Action("throwException")
            public ActionResult throwException(String args) {
                throw new RuntimeException("Intentional exception for testing");
            }

            @Action("throwNPE")
            public ActionResult throwNPE(String args) {
                String s = null;
                return new ActionResult(true, s.length() + ""); // NPE
            }
        }

        @Test
        @DisplayName("Should catch and wrap exception from @Action method")
        void shouldCatchExceptionFromActionMethod() {
            ThrowingClass obj = new ThrowingClass();
            TestIIActorRef<ThrowingClass> actorRef = new TestIIActorRef<>("throwing", obj);

            ActionResult result = actorRef.callByActionName("throwException", "[]");

            assertFalse(result.isSuccess(), "Exception should result in failure");
            assertTrue(result.getResult().contains("Intentional exception"),
                "Error message should contain exception message");
        }

        @Test
        @DisplayName("Should catch NullPointerException from @Action method")
        void shouldCatchNPEFromActionMethod() {
            ThrowingClass obj = new ThrowingClass();
            TestIIActorRef<ThrowingClass> actorRef = new TestIIActorRef<>("throwing", obj);

            ActionResult result = actorRef.callByActionName("throwNPE", "[]");

            assertFalse(result.isSuccess(), "NPE should result in failure");
        }
    }

    // ========================================================================
    // Tests for coexistence with built-in actions
    // ========================================================================

    @Nested
    @DisplayName("Coexistence with Built-in Actions")
    class CoexistenceWithBuiltInActions {

        @Test
        @DisplayName("Built-in JSON actions should still work")
        void builtInJsonActionsShouldWork() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            // putJson is a built-in action in IIActorRef
            ActionResult putResult = actorRef.callByActionName("putJson",
                "{\"path\": \"testKey\", \"value\": \"testValue\"}");
            assertTrue(putResult.isSuccess(), "Built-in putJson should work");

            // getJson is also built-in
            ActionResult getResult = actorRef.callByActionName("getJson", "[\"testKey\"]");
            assertTrue(getResult.isSuccess());
            assertEquals("testValue", getResult.getResult());
        }

        @Test
        @DisplayName("@Action methods should be checked before built-in actions")
        void annotatedActionsShouldBeCheckedFirst() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            // Call custom @Action method
            ActionResult customResult = actorRef.callByActionName("add", "[1, 2]");
            assertTrue(customResult.isSuccess());
            assertEquals("3", customResult.getResult());

            // Call built-in action
            ActionResult builtInResult = actorRef.callByActionName("clearJson", "");
            assertTrue(builtInResult.isSuccess());
        }
    }

    // ========================================================================
    // Tests for caching behavior
    // ========================================================================

    @Nested
    @DisplayName("Action Method Caching")
    class CachingBehavior {

        @Test
        @DisplayName("Multiple calls should use cached method lookup")
        void multipleCallsShouldUseCachedLookup() {
            SimpleCalculator calculator = new SimpleCalculator();
            TestIIActorRef<SimpleCalculator> actorRef = new TestIIActorRef<>("calc", calculator);

            // First call triggers discovery
            ActionResult result1 = actorRef.callByActionName("add", "[1, 1]");
            assertEquals("2", result1.getResult());

            // Second call should use cached lookup
            ActionResult result2 = actorRef.callByActionName("add", "[2, 2]");
            assertEquals("4", result2.getResult());

            // Third call with different action
            ActionResult result3 = actorRef.callByActionName("multiply", "[3, 3]");
            assertEquals("9", result3.getResult());

            // All should succeed
            assertTrue(result1.isSuccess());
            assertTrue(result2.isSuccess());
            assertTrue(result3.isSuccess());
        }
    }
}
