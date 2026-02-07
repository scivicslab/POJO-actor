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

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Compile-time annotation processor for @Action annotation and IIActorRef patterns.
 *
 * <p>This processor performs two validations:</p>
 * <ol>
 *   <li><strong>@Action on non-IIActorRef:</strong> Emits ERROR when @Action is used
 *       on a class that doesn't extend IIActorRef.</li>
 *   <li><strong>callByActionName override:</strong> Emits WARNING when an IIActorRef
 *       subclass overrides callByActionName instead of using @Action.</li>
 * </ol>
 *
 * <h2>Why These Validations?</h2>
 *
 * <p>The @Action annotation mechanism relies on IIActorRef's callByActionName()
 * implementation to discover and invoke annotated methods via reflection.
 * If @Action is placed on a POJO that doesn't extend IIActorRef, the annotation
 * will be silently ignored at runtime.</p>
 *
 * <p>Overriding callByActionName with switch statements is a deprecated pattern.
 * Using @Action annotation is cleaner and more maintainable.</p>
 *
 * <h2>Correct Usage</h2>
 * <pre>{@code
 * public class MyActor extends IIActorRef<Void> {
 *     public MyActor(String name, IIActorSystem system) {
 *         super(name, null, system);
 *     }
 *
 *     @Action("doSomething")
 *     public ActionResult doSomething(String args) {
 *         return new ActionResult(true, "done");
 *     }
 * }
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.15.0
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ActionAnnotationProcessor extends AbstractProcessor {

    private static final String IIACTORREF_CLASS = "com.scivicslab.pojoactor.workflow.IIActorRef";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Check 1: @Action on non-IIActorRef classes (allow interface default methods for mixin pattern)
        for (Element element : roundEnv.getElementsAnnotatedWith(Action.class)) {
            Element enclosingElement = element.getEnclosingElement();
            if (!(enclosingElement instanceof TypeElement)) {
                continue;
            }

            TypeElement enclosingClass = (TypeElement) enclosingElement;

            // Allow @Action on:
            // 1. IIActorRef subclasses
            // 2. Interface default methods (mixin pattern)
            boolean isInterface = enclosingClass.getKind() == ElementKind.INTERFACE;
            boolean isIIActorRefSubclass = extendsIIActorRef(enclosingClass);

            if (!isInterface && !isIIActorRefSubclass) {
                processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    String.format(
                        "@Action annotation can only be used on methods in IIActorRef subclasses " +
                        "or interface default methods (mixin pattern). " +
                        "Class '%s' does not extend IIActorRef. " +
                        "Either extend IIActorRef<T> or use CallableByActionName with switch statement.",
                        enclosingClass.getQualifiedName()
                    ),
                    element
                );
            }
        }

        // Check 2: callByActionName override in IIActorRef subclasses
        for (Element element : roundEnv.getRootElements()) {
            if (element instanceof TypeElement typeElement) {
                if (extendsIIActorRef(typeElement) && !isIIActorRefItself(typeElement)) {
                    checkForCallByActionNameOverride(typeElement);
                }
            }
        }

        return false; // Allow other processors to run
    }

    /**
     * Checks if a class overrides callByActionName and emits a warning.
     */
    private void checkForCallByActionNameOverride(TypeElement typeElement) {
        for (Element member : typeElement.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) member;
                if (isCallByActionNameMethod(method)) {
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        String.format(
                            "Overriding callByActionName() is deprecated. " +
                            "Use @Action annotation on methods instead. " +
                            "See IIActorRef javadoc for the recommended pattern."
                        ),
                        method
                    );
                }
            }
        }
    }

    /**
     * Checks if a method is callByActionName(String, String).
     */
    private boolean isCallByActionNameMethod(ExecutableElement method) {
        if (!method.getSimpleName().toString().equals("callByActionName")) {
            return false;
        }

        var params = method.getParameters();
        if (params.size() != 2) {
            return false;
        }

        // Check both parameters are String
        return params.get(0).asType().toString().equals("java.lang.String")
            && params.get(1).asType().toString().equals("java.lang.String");
    }

    /**
     * Checks if a class extends IIActorRef (directly or indirectly).
     */
    private boolean extendsIIActorRef(TypeElement typeElement) {
        TypeMirror superClass = typeElement.getSuperclass();

        while (superClass != null && !superClass.toString().equals("java.lang.Object")) {
            String superClassName = superClass.toString();

            // Handle generic types: IIActorRef<Void> -> IIActorRef
            if (superClassName.contains("<")) {
                superClassName = superClassName.substring(0, superClassName.indexOf('<'));
            }

            if (superClassName.equals(IIACTORREF_CLASS)) {
                return true;
            }

            // Get the TypeElement for the superclass to continue traversal
            Element superElement = processingEnv.getTypeUtils().asElement(superClass);
            if (superElement instanceof TypeElement) {
                superClass = ((TypeElement) superElement).getSuperclass();
            } else {
                break;
            }
        }

        return false;
    }

    /**
     * Checks if the type is IIActorRef itself (to avoid checking the base class).
     */
    private boolean isIIActorRefItself(TypeElement typeElement) {
        return typeElement.getQualifiedName().toString().equals(IIACTORREF_CLASS);
    }
}
