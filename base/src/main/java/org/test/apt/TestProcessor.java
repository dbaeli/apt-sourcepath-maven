/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.test.apt;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;

import static javax.lang.model.SourceVersion.RELEASE_6;

@SupportedAnnotationTypes(value = "org.test.apt.AptTest")
@SupportedSourceVersion(RELEASE_6)
public class TestProcessor extends AbstractProcessor {

    public TestProcessor() {
      System.out.println("Info processor !!!!!!!!!!!!!!!!!!!!");
    }

   @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        System.out.println("Perform processor !!!!!!!!!!!!!!!!!!!!");

        if (roundEnv.processingOver()) {
            return true;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(AptTest.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            final TypeElement bundleType = (TypeElement) element;
            System.out.println("Matched " + bundleType.getSimpleName());
            try {
                FileObject file = processingEnv.getFiler().createSourceFile("MyFile"+bundleType.getSimpleName(), bundleType);
                final Writer writer = file.openWriter();
                writer.write("public class MyFile" + bundleType.getSimpleName() + " {}\n");
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), bundleType);
            }
        }
        return false;
    }

}

