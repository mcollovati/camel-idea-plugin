/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.idea.util;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.intellij.codeInsight.completion.CompletionUtil;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiConstructorCall;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.psi.xml.XmlToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.xml.CommonXmlStrings.QUOT;

/**
 * Utility methods to work with IDEA {@link PsiElement}s.
 * <p/>
 * This class is only for IDEA APIs. If you need Camel related APIs as well then use {@link CamelIdeaUtils} instead.
 */
public final class IdeaUtils {

    private static final List<String> ROUTE_BUILDER_OR_EXPRESSION_CLASS_QUALIFIED_NAME = Arrays.asList(
        "org.apache.camel.builder.RouteBuilder", "org.apache.camel.builder.BuilderSupport",
        "org.apache.camel.model.ProcessorDefinition", "org.apache.camel.model.language.ExpressionDefinition");

    private IdeaUtils() {
    }

    /**
     * Extract the text value from the {@link PsiElement} from any of the support languages this plugin works with.
     *
     * @param element the element
     * @return the text or <tt>null</tt> if the element is not a text/literal kind.
     */
    @Nullable
    public static String extractTextFromElement(PsiElement element) {
        return extractTextFromElement(element, true, false, true);
    }

    /**
     * Extract the text value from the {@link PsiElement} from any of the support languages this plugin works with.
     *
     * @param element the element
     * @param fallBackToGeneric if could find any of the supported languages fallback to generic if true
     * @param concatString concatenated the string if it wrapped
     * @param stripWhitespace
     * @return the text or <tt>null</tt> if the element is not a text/literal kind.
     */
    @Nullable
    public static String extractTextFromElement(PsiElement element, boolean fallBackToGeneric, boolean concatString, boolean stripWhitespace) {

        if (element instanceof PsiLiteralExpression) {
            // need the entire line so find the literal expression that would hold the entire string (java)
            PsiLiteralExpression literal = (PsiLiteralExpression) element;
            Object o = literal.getValue();
            String text = o != null ? o.toString() : null;
            if (text == null) {
                return "";
            }
            if (concatString) {
                final PsiPolyadicExpression parentOfType = PsiTreeUtil.getParentOfType(element, PsiPolyadicExpression.class);
                if (parentOfType != null) {
                    text = parentOfType.getText();
                }
            }
            // unwrap literal string which can happen in java too
            if (stripWhitespace) {
                return getInnerText(text);
            }
            return StringUtil.unquoteString(text.replace(QUOT, "\""));
        }

        // maybe its xml then try that
        if (element instanceof XmlAttributeValue) {
            return ((XmlAttributeValue) element).getValue();
        } else if (element instanceof XmlText) {
            return ((XmlText) element).getValue();
        } else if (element instanceof XmlToken) {
            // it may be a token which is a part of an combined attribute
            if (concatString) {
                XmlAttributeValue xml = PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class);
                if (xml != null) {
                    String value = getInnerText(xml.getValue());
                    return value;
                }
            } else {
                String returnText = element.getText();
                final PsiElement prevSibling = element.getPrevSibling();
                if (prevSibling != null && prevSibling.getText().equalsIgnoreCase("&amp;")) {
                    returnText = prevSibling.getText() + returnText;
                }
                return getInnerText(returnText);
            }
        }

        // its maybe a property from properties file
        String fqn = element.getClass().getName();
        if (fqn.startsWith("com.intellij.lang.properties.psi.impl.PropertyValue")) {
            // yes we can support this also
            return element.getText();
        }

        // maybe its yaml
        if (element instanceof LeafPsiElement) {
            IElementType type = ((LeafPsiElement) element).getElementType();
            if (type.getLanguage().isKindOf("yaml")) {
                return element.getText();
            }
        }

        // maybe its groovy
        if (element instanceof LeafPsiElement) {
            IElementType type = ((LeafPsiElement) element).getElementType();
            if (type.getLanguage().isKindOf("Groovy")) {
                String text = element.getText();
                // unwrap groovy gstring
                return getInnerText(text);
            }
        }

        // maybe its scala
        if (element instanceof LeafPsiElement) {
            IElementType type = ((LeafPsiElement) element).getElementType();
            if (type.getLanguage().isKindOf("Scala")) {
                String text = element.getText();
                // unwrap scala string
                return getInnerText(text);
            }
        }

        // maybe its kotlin
        if (element instanceof LeafPsiElement) {
            IElementType type = ((LeafPsiElement) element).getElementType();
            if (type.getLanguage().isKindOf("kotlin")) {
                String text = element.getText();
                // unwrap kotlin string
                return getInnerText(text);
            }
        }

        if (fallBackToGeneric) {
            // fallback to generic
            String text = element.getText();
            if (concatString) {
                final PsiPolyadicExpression parentOfType = PsiTreeUtil.getParentOfType(element, PsiPolyadicExpression.class);
                if (parentOfType != null) {
                    text = parentOfType.getText();
                }
            }
            // the text may be quoted so unwrap that
            if (stripWhitespace) {
                return getInnerText(text);
            }
            return StringUtil.unquoteString(text.replace(QUOT, "\""));
        }

        return null;
    }

    /**
     * Is the element from a java setter method (eg setBrokerURL) or from a XML configured <tt>bean</tt> style
     * configuration using <tt>property</tt> element.
     */
    public static boolean isElementFromSetterProperty(@NotNull PsiElement element, @NotNull String setter) {
        // java method call
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
        if (call != null) {
            PsiMethod resolved = call.resolveMethod();
            if (resolved != null) {
                String javaSetter = "set" + Character.toUpperCase(setter.charAt(0)) + setter.substring(1);
                return javaSetter.equals(resolved.getName());
            }
            return false;
        }

        // its maybe an XML property
        XmlTag xml = PsiTreeUtil.getParentOfType(element, XmlTag.class);
        if (xml != null) {
            boolean bean = isFromXmlTag(xml, "bean", "property");
            if (bean) {
                String key = xml.getAttributeValue("name");
                return setter.equals(key);
            }
            return false;
        }

        return false;
    }

    /**
     * Is the element from a java annotation with the given name.
     */
    public static boolean isElementFromAnnotation(@NotNull PsiElement element, @NotNull String annotationName) {
        // java method call
        PsiAnnotation ann = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (ann != null) {
            return annotationName.equals(ann.getQualifiedName());
        }

        return false;
    }

    /**
     * Is the element from Java language
     */
    public static boolean isJavaLanguage(PsiElement element) {
        return element != null && PsiUtil.getNotAnyLanguage(element.getNode()).is(JavaLanguage.INSTANCE);
    }

    /**
     * Is the element from Groovy language
     */
    public static boolean isGroovyLanguage(PsiElement element) {
        return element != null && PsiUtil.getNotAnyLanguage(element.getNode()).isKindOf("Groovy");
    }

    /**
     * Is the element from Scala language
     */
    public static boolean isScalaLanguage(PsiElement element) {
        return element != null && PsiUtil.getNotAnyLanguage(element.getNode()).isKindOf("Scala");
    }

    /**
     * Is the element from Kotlin language
     */
    public static boolean isKotlinLanguage(PsiElement element) {
        return element != null && PsiUtil.getNotAnyLanguage(element.getNode()).isKindOf("kotlin");
    }

    /**
     * Is the element from XML language
     */
    public static boolean isXmlLanguage(PsiElement element) {
        return element != null && PsiUtil.getNotAnyLanguage(element.getNode()).is(XMLLanguage.INSTANCE);
    }

    /**
     * Is the element from a file of the given extensions such as <tt>java</tt>, <tt>xml</tt>, etc.
     */
    public static boolean isFromFileType(PsiElement element, @NotNull String... extensions) {
        if (extensions.length == 0) {
            throw new IllegalArgumentException("Extension must be provided");
        }

        PsiFile file;
        if (element instanceof PsiFile) {
            file = (PsiFile) element;
        } else {
            file = PsiTreeUtil.getParentOfType(element, PsiFile.class);
        }
        if (file != null) {
            String name = file.getName().toLowerCase();
            for (String match : extensions) {
                if (name.endsWith("." + match.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates a URLClassLoader for a given library or libraries
     *
     * @param libraries the library or libraries
     * @return the classloader
     */
    public static @Nullable URLClassLoader newURLClassLoaderForLibrary(Library... libraries) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (Library library : libraries) {
            if (library != null) {
                VirtualFile[] files = library.getFiles(OrderRootType.CLASSES);
                if (files.length == 1) {
                    VirtualFile vf = files[0];
                    if (vf.getName().toLowerCase().endsWith(".jar")) {
                        String path = vf.getPath();
                        if (path.endsWith("!/")) {
                            path = path.substring(0, path.length() - 2);
                        }
                        URL url = new URL("file:" + path);
                        urls.add(url);
                    }
                }
            }
        }
        if (urls.isEmpty()) {
            return null;
        }

        URL[] array = urls.toArray(new URL[urls.size()]);
        return new URLClassLoader(array);
    }

    /**
     * Is the given class or any of its super classes a class with the qualified name.
     *
     * @param target  the class
     * @param fqnClassName the class name to match
     * @return <tt>true</tt> if the class is a type or subtype of the class name
     */
    public static boolean isClassOrParentOf(@Nullable PsiClass target, @NotNull String fqnClassName) {
        if (target == null) {
            return false;
        }
        if (target.getQualifiedName().equals(fqnClassName)) {
            return true;
        } else {
            return isClassOrParentOf(target.getSuperClass(), fqnClassName);
        }
    }

    /**
     * Is the element from a constructor call with the given constructor name (eg class name)
     *
     * @param element  the element
     * @param constructorName the name of the constructor (eg class)
     * @return <tt>true</tt> if its a constructor call from the given name, <tt>false</tt> otherwise
     */
    public static boolean isElementFromConstructor(@NotNull PsiElement element, @NotNull String constructorName) {
        // java constructor
        PsiConstructorCall call = PsiTreeUtil.getParentOfType(element, PsiConstructorCall.class);
        if (call != null) {
            PsiMethod resolved = call.resolveConstructor();
            if (resolved != null) {
                return constructorName.equals(resolved.getName());
            }
        }
        return false;
    }

    /**
     * Is the given element from a Java method call with any of the given method names
     *
     * @param element  the psi element
     * @param methods  method call names
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean isFromJavaMethodCall(PsiElement element, boolean fromRouteBuilder, String... methods) {
        // java method call
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
        if (call != null) {
            return doIsFromJavaMethod(call, fromRouteBuilder, methods);
        }
        return false;
    }

    private static boolean doIsFromJavaMethod(PsiMethodCallExpression call, boolean fromRouteBuilder, String... methods) {
        PsiMethod method = call.resolveMethod();
        if (method != null) {
            PsiClass containingClass = method.getContainingClass();
            if (containingClass != null) {
                String name = method.getName();
                // TODO: this code should likely be moved to something that requires it from being a Camel RouteBuilder
                if (Arrays.stream(methods).anyMatch(name::equals)) {
                    if (fromRouteBuilder) {
                        return ROUTE_BUILDER_OR_EXPRESSION_CLASS_QUALIFIED_NAME.stream().anyMatch((t) -> isClassOrParentOf(containingClass, t));
                    } else {
                        return true;
                    }
                }
            }
        } else {
            // TODO : This should be removed when we figure how to setup language depend SDK classes
            // alternative when we run unit test where IDEA causes the method call expression to include their dummy hack which skews up this logic
            PsiElement child = call.getFirstChild();
            if (child != null) {
                child = child.getLastChild();
            }
            if (child != null && child instanceof PsiIdentifier) {
                String name = child.getText();
                return Arrays.stream(methods).anyMatch(name::equals);
            }
        }
        return false;
    }

    /**
     * Is the given element from a XML tag with any of the given tag names
     *
     * @param xml  the xml tag
     * @param methods  xml tag names
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean isFromXmlTag(@NotNull XmlTag xml, @NotNull String... methods) {
        String name = xml.getLocalName();
        return Arrays.stream(methods).anyMatch(name::equals);
    }

    /**
     * Is the given element from a XML tag with any of the given tag names
     *
     * @param xml  the xml tag
     * @param parentTag a special parent tag name to match first
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean hasParentXmlTag(@NotNull XmlTag xml, @NotNull String parentTag) {
        XmlTag parent = xml.getParentTag();
        return parent != null && parent.getLocalName().equals(parentTag);
    }

    /**
     * Is the given element from a XML tag with the parent and is of any of the given tag names
     *
     * @param xml  the xml tag
     * @param parentTag a special parent tag name to match first
     * @param methods  xml tag names
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean hasParentAndFromXmlTag(@NotNull XmlTag xml, @NotNull String parentTag, @NotNull String... methods) {
        return hasParentXmlTag(xml, parentTag) && isFromFileType(xml, methods);
    }

    /**
     * Is the given element from a Groovy method call with any of the given method names
     *
     * @param element  the psi element
     * @param methods  method call names
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean isFromGroovyMethod(PsiElement element, String... methods) {
        // need to walk a bit into the psi tree to find the element that holds the method call name
        // must be a groovy string kind
        String kind = element.toString();
        if (kind.contains("Gstring") || kind.contains("(string)")) {
            PsiElement parent = element.getParent();
            if (parent != null) {
                parent = parent.getParent();
            }
            if (parent != null) {
                element = parent.getPrevSibling();
            }
            if (element != null) {
                element = element.getLastChild();
            }
        }
        if (element != null) {
            kind = element.toString();
            // must be an identifier which is part of the method call
            if (kind.contains("identifier")) {
                String name = element.getText();
                return Arrays.stream(methods).anyMatch(name::equals);
            }
        }
        return false;
    }

    public static boolean isPrevSiblingFromGroovyMethod(PsiElement element, String... methods) {
        boolean found = false;

        // need to walk a bit into the psi tree to find the element that holds the method call name
        // must be a groovy string kind
        String kind = element.toString();
        if (kind.contains("Gstring") || kind.contains("(string)")) {

            // there are two ways to dig into the groovy ast so try first and then second
            PsiElement first = element.getParent();
            if (first != null) {
                first = first.getParent();
            }
            if (first != null) {
                first = first.getPrevSibling();
            }
            if (first != null) {
                first = first.getFirstChild();
            }
            if (first != null) {
                first = first.getFirstChild();
            }
            if (first != null) {
                first = first.getLastChild();
            }
            if (first != null) {
                kind = first.toString();
                found = kind.contains("identifier");
                if (found) {
                    element = first;
                }
            }

            if (!found) {
                PsiElement second = element.getParent();
                if (second != null) {
                    second = second.getParent();
                }
                if (second != null) {
                    second = second.getParent();
                }
                if (second != null) {
                    second = second.getPrevSibling();
                }
                if (second != null) {
                    second = second.getParent();
                }
                if (second != null) {
                    second = second.getPrevSibling();
                }
                if (second != null) {
                    second = second.getLastChild();
                }
                if (second != null) {
                    kind = second.toString();
                    found = kind.contains("identifier");
                    if (found) {
                        element = second;
                    }
                }
            }
        }

        if (found) {
            kind = element.toString();
            // must be an identifier which is part of the method call
            if (kind.contains("identifier")) {
                String name = element.getText();
                return Arrays.stream(methods).anyMatch(name::equals);
            }
        }
        return false;
    }

    /**
     * Is the given element from a Scala method call with any of the given method names
     *
     * @param element  the psi element
     * @param methods  method call names
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean isFromScalaMethod(PsiElement element, String... methods) {
        // need to walk a bit into the psi tree to find the element that holds the method call name
        // (yes we need to go up till 5 levels up to find the method call expression
        //System.out.println("========== Inspect " + element.getText() + " --> " + element.toString());
        Optional<PsiElement> isLiteral = asScalaLiteralExpression(element);
        /*
        isLiteral.ifPresent(el ->
            System.out.println("============ Literal " + element.getText() + " of type " + element.toString() + " with parent " +
                Optional.ofNullable(element.getParent()).map(PsiElement::toString).orElse("---"))
        );
        */
        Optional<PsiElement> isIdentifier = asScalaIdentifierExpression(element);
        /*
        isIdentifier.ifPresent(el ->
            System.out.println("============ Identifier " + element.getText() + " of type " + element.toString() + " with parent " +
                Optional.ofNullable(element.getParent()).map(PsiElement::toString).orElse("---") +
            " resolved: " + el.toString())
        );
        */
        return (isLiteral.isPresent() || isIdentifier.isPresent())
            && Optional.ofNullable(PsiTreeUtil.findFirstParent(element, true, parent -> "MethodCall".equals(parent.toString())))
            .flatMap(methodCall -> Optional.ofNullable(element.getParent())
                .map(PsiElement::getParent).filter(twoUp -> !methodCall.equals(twoUp))
                .map(x -> methodCall)).map(PsiElement::getFirstChild)
            .map(PsiElement::getText).filter(name -> Arrays.stream(methods).anyMatch(name::equals))
            .isPresent();
    }

    public static Optional<PsiElement> asScalaLiteralExpression(PsiElement element) {
        Objects.requireNonNull(element);
        String kind = element.toString();
        if (kind.startsWith("PsiElement") && kind.contains("string content")) {
            return Optional.ofNullable(element.getParent())
                .filter(parent -> parent.toString().equals("Literal"))
                .map(parent -> element);
        }
        return Optional.empty();
    }

    public static Optional<PsiElement> asScalaIdentifierExpression(PsiElement element) {
        Objects.requireNonNull(element);
        String kind = element.toString();
        if (kind.startsWith("PsiElement") && kind.contains("identifier")) {
            return Optional.ofNullable(element.getParent())
                .filter(parent -> parent.toString().startsWith("ReferenceExpression:"))
                .map(PsiElement::getReference)
                .map(PsiReference::resolve)
                .filter(el ->
                    el.toString().startsWith("ReferencePattern: ")
                        || el.toString().startsWith("ScFunctionDefinition: ")
                );
        }
        return Optional.empty();
    }


    public static boolean isFromScalaMethodOld(PsiElement element, String... methods) {
        // need to walk a bit into the psi tree to find the element that holds the method call name
        // (yes we need to go up till 5 levels up to find the method call expression
        String kind = element.toString();

        // must be a string kind
        if (kind.contains("string")) {
            for (int i = 0; i < 5; i++) {
                if (element != null) {
                    kind = element.toString();
                    if ("MethodCall".equals(kind)) {
                        element = element.getFirstChild();
                        if (element != null) {
                            String name = element.getText();
                            return Arrays.stream(methods).anyMatch(name::equals);
                        }
                    }
                    if (element != null) {
                        element = element.getParent();
                    }
                }
            }
        }
        return false;
    }

    public static boolean isPrevSiblingFromScalaMethod(PsiElement element, String... methods) {
        boolean found = false;

        // need to walk a bit into the psi tree to find the element that holds the method call name
        // must be a scala string kind
        String kind = element.toString();
        if (kind.contains("string")) {

            // there are two ways to dig into the groovy ast so try first and then second
            PsiElement first = element.getParent();
            if (first != null) {
                first = first.getPrevSibling();
            }
            if (first != null) {
                first = first.getParent();
            }
            if (first != null) {
                first = first.getPrevSibling();
            }
            if (first != null) {
                first = first.getParent();
            }
            if (first != null) {
                first = first.getPrevSibling();
            }
            if (first != null) {
                first = first.getParent();
            }
            if (first != null) {
                first = first.getPrevSibling();
            }
            if (first != null) {
                first = first.getFirstChild();
            }

            if (first != null) {
                kind = first.toString();
                found = kind.contains("identifier");
                if (found) {
                    element = first;
                }
            }

        }

        if (found) {
            kind = element.toString();
            if (kind.contains("identifier")) {
                String name = element.getText();
                return Arrays.stream(methods).anyMatch(name::equals);
            }
        }
        return false;
    }

    /**
     * Is the given element from a Kotlin method call with any of the given method names
     *
     * @param element  the psi element
     * @param methods  method call names
     * @return <tt>true</tt> if matched, <tt>false</tt> otherwise
     */
    public static boolean isFromKotlinMethod(PsiElement element, String... methods) {
        // need to walk a bit into the psi tree to find the element that holds the method call name
        // (yes we need to go up till 6 levels up to find the method call expression
        String kind = element.toString();
        // must be a string kind
        if (kind.contains("STRING")) {
            for (int i = 0; i < 6; i++) {
                if (element != null) {
                    kind = element.toString();
                    if ("CALL_EXPRESSION".equals(kind)) {
                        element = element.getFirstChild();
                        if (element != null) {
                            String name = element.getText();
                            return Arrays.stream(methods).anyMatch(name::equals);
                        }
                    }
                    if (element != null) {
                        element = element.getParent();
                    }
                }
            }
        }
        return false;
    }

    /**
     * Code from com.intellij.psi.impl.source.tree.java.PsiLiteralExpressionImpl#getInnerText()
     */
    @Nullable
    public static String getInnerText(String text) {
        if (text == null) {
            return null;
        }
        if (StringUtil.endsWithChar(text, '\"') && text.length() == 1) {
            return "";
        }
        // Remove any newline feed + whitespaces + single + double quot to concat a split string
        return StringUtil.unquoteString(text.replace(QUOT, "\"")).replaceAll("(^\\n\\s+|\\n\\s+$|\\n\\s+)|(\"\\s*\\+\\s*\")|(\"\\s*\\+\\s*\\n\\s*\"*)", "");
    }

    public static int getCaretPositionInsidePsiElement(String stringLiteral) {
        String hackVal = stringLiteral.toLowerCase();

        int hackIndex = hackVal.indexOf(CompletionUtil.DUMMY_IDENTIFIER.toLowerCase());
        if (hackIndex == -1) {
            hackIndex = hackVal.indexOf(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED.toLowerCase());
        }
        return hackIndex;
    }


    /**
     * Return the Query parameter at the cursor location for the query parameter.
     *  <ul>
     *    <li>timer:trigger?repeatCount=0&de&lt;cursor&gt; will return {"&de", null}</li>
     *    <li>timer:trigger?repeatCount=0&de&lt;cursor&gt;lay=10 will return {"&de",null}</li>
     *    <li>timer:trigger?repeatCount=0&delay=10&lt;cursor&gt; will return {"delay","10"}</li>
     *    <li>timer:trigger?repeatCount=0&delay=&lt;cursor&gt; will return {"delay",""}</li>
     *    <li>jms:qu&lt;cursor&gt; will return {":qu", ""}</li>
     *  </ul>
     * @return a list with the query parameter and the value if present. The query parameter is returned with separator char
     */
    public static String[] getQueryParameterAtCursorPosition(PsiElement element) {
        String positionText = IdeaUtils.extractTextFromElement(element);
        positionText = positionText.replaceAll("&amp;", "&");

        int hackIndex = getCaretPositionInsidePsiElement(positionText);
        positionText = positionText.substring(0, hackIndex);
        //we need to know the start position of the unknown options
        int startIdx = Math.max(positionText.lastIndexOf('.'), positionText.lastIndexOf('='));
        startIdx = Math.max(startIdx, positionText.lastIndexOf('&'));
        startIdx = Math.max(startIdx, positionText.lastIndexOf('?'));
        startIdx = Math.max(startIdx, positionText.lastIndexOf(':'));

        startIdx = startIdx < 0 ? 0 : startIdx;

        //Copy the option with any separator chars
        String parameter;
        String value = null;
        if (!positionText.isEmpty() && positionText.charAt(startIdx) == '=') {
            value = positionText.substring(startIdx + 1, hackIndex);
            int valueStartIdx = positionText.lastIndexOf('&', startIdx);
            valueStartIdx = Math.max(valueStartIdx, positionText.lastIndexOf('?'));
            valueStartIdx = Math.max(valueStartIdx, positionText.lastIndexOf(':'));
            valueStartIdx = valueStartIdx < 0 ? 0 : valueStartIdx;
            parameter = positionText.substring(valueStartIdx, startIdx);
        } else {
            //Copy the option with any separator chars
            parameter = positionText.substring(startIdx, hackIndex);
        }

        return new String[]{parameter, value};
    }

    public static boolean isCaretAtEndOfLine(PsiElement element) {
        String value = IdeaUtils.extractTextFromElement(element).trim();

        if (value != null) {
            value = value.toLowerCase();
            return value.endsWith(CompletionUtil.DUMMY_IDENTIFIER.toLowerCase())
                || value.endsWith(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED.toLowerCase());
        }

        return false;
    }

}
