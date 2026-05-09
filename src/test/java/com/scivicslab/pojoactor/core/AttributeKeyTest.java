package com.scivicslab.pojoactor.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AttributeKey typed attribute storage on ActorRef.
 * No ActorSystem required — attributes are stored on the ActorRef directly.
 */
@Tag("S1.08")
class AttributeKeyTest {

    static final AttributeKey<Long>    START_TIME  = AttributeKey.of("startTime",  Long.class);
    static final AttributeKey<String>  REQUEST_ID  = AttributeKey.of("requestId",  String.class);
    static final AttributeKey<Boolean> FORCE_BUILD = AttributeKey.of("forceBuild", Boolean.class);

    private ActorRef<Object> freshRef() {
        return new ActorRef<>("test", new Object());
    }

    @Test
    void getAttribute_returnsNull_whenNotSet() {
        ActorRef<Object> ref = freshRef();
        assertNull(ref.getAttribute(START_TIME));
        ref.close();
    }

    @Test
    void putAndGetAttribute_roundtrips_correctValue() {
        ActorRef<Object> ref = freshRef();
        ref.putAttribute(REQUEST_ID, "req-123");
        assertEquals("req-123", ref.getAttribute(REQUEST_ID));
        ref.close();
    }

    @Test
    void getAttribute_withDefault_returnsDefaultWhenAbsent() {
        ActorRef<Object> ref = freshRef();
        assertEquals("fallback", ref.getAttribute(REQUEST_ID, "fallback"));
        ref.close();
    }

    @Test
    void getAttribute_withDefault_returnsStoredValueWhenPresent() {
        ActorRef<Object> ref = freshRef();
        ref.putAttribute(REQUEST_ID, "req-456");
        assertEquals("req-456", ref.getAttribute(REQUEST_ID, "fallback"));
        ref.close();
    }

    @Test
    void hasAttribute_returnsFalse_beforePut() {
        ActorRef<Object> ref = freshRef();
        assertFalse(ref.hasAttribute(START_TIME));
        ref.close();
    }

    @Test
    void hasAttribute_returnsTrue_afterPut() {
        ActorRef<Object> ref = freshRef();
        ref.putAttribute(START_TIME, 1000L);
        assertTrue(ref.hasAttribute(START_TIME));
        ref.close();
    }

    @Test
    void removeAttribute_returnsValue_andClearsKey() {
        ActorRef<Object> ref = freshRef();
        ref.putAttribute(REQUEST_ID, "req-789");
        assertEquals("req-789", ref.removeAttribute(REQUEST_ID));
        assertFalse(ref.hasAttribute(REQUEST_ID));
        assertNull(ref.getAttribute(REQUEST_ID));
        ref.close();
    }

    @Test
    void removeAttribute_returnsNull_whenNotSet() {
        ActorRef<Object> ref = freshRef();
        assertNull(ref.removeAttribute(REQUEST_ID));
        ref.close();
    }

    @Test
    void differentKeys_areIndependent() {
        ActorRef<Object> ref = freshRef();
        ref.putAttribute(START_TIME, 42L);
        ref.putAttribute(FORCE_BUILD, true);

        assertEquals(42L, ref.getAttribute(START_TIME));
        assertEquals(true, ref.getAttribute(FORCE_BUILD));
        assertNull(ref.getAttribute(REQUEST_ID));
        ref.close();
    }

    @Test
    void putAttribute_overwritesPreviousValue() {
        ActorRef<Object> ref = freshRef();
        ref.putAttribute(REQUEST_ID, "first");
        ref.putAttribute(REQUEST_ID, "second");
        assertEquals("second", ref.getAttribute(REQUEST_ID));
        ref.close();
    }

    @Test
    void putAttribute_throwsOnTypeMismatch() {
        ActorRef<Object> ref = freshRef();
        // START_TIME expects Long; passing String should throw
        assertThrows(IllegalArgumentException.class, () ->
            ref.putAttribute((AttributeKey) START_TIME, "not-a-long"));
        ref.close();
    }

    @Test
    void attributeKey_equalityByNameAndType() {
        AttributeKey<String> k1 = AttributeKey.of("requestId", String.class);
        AttributeKey<String> k2 = AttributeKey.of("requestId", String.class);
        AttributeKey<Long>   k3 = AttributeKey.of("requestId", Long.class);

        assertEquals(k1, k2);
        assertNotEquals(k1, k3);
    }
}
