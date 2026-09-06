package com.scivicslab.pojoactor.core;

/**
 * What a dynamically loaded plugin implements in these tests.
 *
 * <p>Defined here rather than reusing a framework interface: {@link DynamicActorLoader} loads any
 * object, and requiring one that dispatches by action name would test Turing-workflow's vocabulary
 * instead of the loader's.
 */
public interface TestCalculator {

    /** @param args two integers separated by a comma */
    String add(String args);

    /** @return the value the last call produced */
    String lastResult();
}
