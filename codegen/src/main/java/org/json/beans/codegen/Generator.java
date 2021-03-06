/*
 * The MIT License
 *
 * Copyright 2021 Mingun.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.json.beans.codegen;

import com.helger.jcodemodel.AbstractJClass;
import com.helger.jcodemodel.AbstractJType;
import com.helger.jcodemodel.EClassType;
import com.helger.jcodemodel.IJClassContainer;
import com.helger.jcodemodel.JClassAlreadyExistsException;
import com.helger.jcodemodel.JCodeModel;
import com.helger.jcodemodel.JDefinedClass;
import com.helger.jcodemodel.JMod;
import com.helger.jcodemodel.writer.JCMWriter;
import com.helger.jcodemodel.writer.OutputStreamCodeWriter;
import java.io.File;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.jimblackler.jsonschemafriend.Schema;
import net.jimblackler.jsonschemafriend.SchemaStore;
import org.json.beans.JsonBoolean;
import org.json.beans.JsonInteger;
import org.json.beans.JsonNull;
import org.json.beans.JsonNumber;
import org.json.beans.JsonScalar;
import org.json.beans.JsonString;

/**
 *
 * @author Mingun
 */
public class Generator {
  /** ?????????????????? Java ????????. */
  private final JCodeModel model = new JCodeModel();

  public static void main(String[] args) throws Exception {
    final SchemaStore store = new SchemaStore();

    final Schema schema = store.loadSchema(new File(args[0]));
    new Generator().generate(schema);
  }
  public void generate(Schema schema) throws Exception {
    final Path name = Paths.get(schema.getUri().getPath()).getFileName();
    generateTopLevel(toTitleCase(name.toString()), schema);

    for (final Schema s : schema.getSubSchemas().values()) {
      if (isTopLevel(s)) {
        generateTopLevel(className(s), s);
      }
    }

    final JCMWriter writer = new JCMWriter(model);
    writer.build(new OutputStreamCodeWriter(System.out, UTF_8));
  }
  /**
   * ???????????????????? ??????????, ?? ???????????????? ?? ?????????? ???????? ???????????? ?????????????????????? ??????, ???????? ?????????? ?????? ?????????? ??????????
   * JSON ??????????????????. ?????????? ????????, ?????????????????? ?????????????? ?????????????????? ?? ?????????????????????? ?? ??????????, ???? ?????????????? ??????
   * ???????? ?????????????????????????? (?????? ???????????????????? ?????????????? ?????? ???????????????????? ???????????????? -- ?????? ?????????????????? ?? ???? ????????????????
   * ???????????????????????? ????????????)
   *
   * @param className ?????? ?????????????????????????? ????????????
   * @param schema ??????????, ?????? ?????????????? ???????????????????????? ??????????
   *
   * @return ?????????????????? ??????????
   *
   * @throws JClassAlreadyExistsException ?????????? ???????????????????? ?????????????? ???? ???????????? ???????????????? ?????? ????????????????????
   *         ??????????, ??.??. ?????? ?????????? ?????????? ??????????????????????, ?? ?????????? ????????????????????????, ???????????? ?????? ???????? ????????????????????
   *         ????????????????????
   */
  private AbstractJClass generateTopLevel(String className, Schema schema) throws JClassAlreadyExistsException {
    final AbstractJClass clazz = generate(model.rootPackage(), className, false, schema);
    return clazz;
  }
  /**
   * ???????????????????? ??????????, ???????????????????????????? ??????, ?????????????????????? ????????????
   *
   * @param container ????????????, ???????????? ???????????????? ???????????????????????? ?????????? ????????????
   * @param baseClassName ?????????????? ?????? ?????? ????????????. ???????????????? ?????????? ???????? ???????????????????????? ?????????? ???????????????????? ????????????????
   * @param inner ???????? ?????????? ????????????????????, ???? ???????? ?????????? ?????????????????? ?????????????? {@code Type} ?????? {@code Enum}.
   *        ???????????????????? ???????????? ???????????????????????? ?????? ????????, ???? ?????????????????????? ???? ?????????????? ????????????
   * @param schema ??????????, ?????? ?????????????? ???????????????????????? ??????????
   *
   * @return ?????????????????? ??????????
   *
   * @throws JClassAlreadyExistsException ?????????? ???????????????????? ?????????????? ???? ???????????? ???????????????? ?????? ????????????????????
   *         ??????????, ??.??. ?????? ?????????? ?????????? ??????????????????????, ?? ?????????? ????????????????????????, ???????????? ?????? ???????? ????????????????????
   *         ????????????????????
   */
  private AbstractJClass generate(IJClassContainer<JDefinedClass> container, String baseClassName, boolean inner, Schema schema) throws JClassAlreadyExistsException {
    final List<Object> enums = schema.getEnums();
    final Collection<String> types = schema.getExplicitTypes();
    // ???????? ?????? ???? ???????????????? ?????????????????????????? ?? ???????????????? ???????????? ???????? ??????????????, ?????????????????????? ????????????,
    // ???????? ?????? ????????????????, ???????? ???? ?????????????? ????????????, ?? ???????????? -- "null", ???? ?????????????????? ???????????? ???? ??????????????????,
    // ???????????????????? ???????????????????? ??????
    if (enums == null && types != null) {
      final Class<?> scalar = toScalar(types);
      if (scalar != null) {
        return model.ref(scalar);
      }
    }

    final JDefinedClass clazz = container._class(
        JMod.PUBLIC,
        inner ? baseClassName + (enums != null ? "Enum" : "Type") : baseClassName,
        enums != null ? EClassType.ENUM : EClassType.INTERFACE
    );

    generateVariant(schema, schema.getOneOf(), clazz);
    generateVariant(schema, schema.getAnyOf(), clazz);

    // ???????? ???????????????? ?????????????????????????? ???????? ???????????? ????????????????????????, ???? ?????? ?????????? ???????? ???????????????????????? ?????? ??????????
    // ???? ?????????? ?????? ???????? ??????????, ?? ??????????????????????????, ?????????? ???????????? ?????????????????????????? ??????????, ?????????????????????? ????
    // ???????? ???????? ??????????
    final Collection<Schema> allOf = schema.getAllOf();
    if (allOf != null) {
      int i = 0;
      for (final Schema s : allOf) {
        clazz._implements(generate(clazz, schema, s, "Variant" + i++));
      }
    }

    // ?????????????? ?????? ???????? "???????????????? ????????????????" -> "?????????? ??????????????????" ?? ???????????????????? ???????????? ??????????????.
    for (final Map.Entry<String, Schema> e : schema.getProperties().entrySet()) {
      final String prop = e.getKey();
      final Schema s = e.getValue();
      final AbstractJClass cls = generate(clazz, schema, s, toTitleCase(prop));

      clazz.method(JMod.PUBLIC, cls, resolveCollision(clazz, getterName(prop)));
    }

    return clazz;
  }
  /**
   * ???????????????????? ?????????? ?????????????? ?????????????????? ?????????????????????????? ?????????????? ?? ???????? ???????????? ???? ??????????, ??????????????????????????????
   * ???????????? ?? {@code variants}.
   *
   * @param parent ??????????, ?? ?????????????????? ?????????????? ???????????????????????????? ??????????????????
   * @param variants ?????????????????? ????????, ?????? ?????????????? ???????????????????? ?????????????????????????? ?? ???????????? {@code clazz}
   *        ?????????????? ???????? {@code as<??????>}
   * @param clazz ??????????, ?? ?????????????? ???????????????????????? ????????????
   *
   * @throws JClassAlreadyExistsException ?????????? ???????????????????? ?????????????? ???? ???????????? ???????????????? ?????? ????????????????????
   *         ??????????, ??.??. ?????? ?????????? ?????????? ??????????????????????, ?? ?????????? ????????????????????????, ???????????? ?????? ???????? ????????????????????
   *         ????????????????????
   */
  private void generateVariant(Schema parent, Collection<Schema> variants, JDefinedClass clazz) throws JClassAlreadyExistsException {
    if (variants != null) {
      int i = 0;
      for (final Schema schema : variants) {
        // ?????????????? ?????? ?????? ????????, ?????????????????????????????? ???????????????????? ??????????, ?????????????????????? ???? ??????????
        String name = "Variant" + i++;
        final AbstractJClass type = generate(clazz, parent, schema, name);
        // ???????? ?????????? ???????? ?????????????????? ???? ?????????? ?? ???? ???????????????? ?????????????????????? (??????????, ?????? { type: "string" }),
        // ???? ???????????????????? ?????? ?????????? ???????????????? ???????????? ???????????? ?? ???? ?????????? ???????????????????????? ?????? VariantN.
        // ?? ?????????????????? ???????????? ???????????????????? ?????? ????????????
        if (!clazz.classes().contains(type)) {
          name = type.name();
        }

        name = resolveCollision(clazz, "as" + name);
        clazz.method(JMod.PUBLIC, type, name);
      }
    }
  }
  /**
   * ???????????????????? ?????????? ?????? ?????????? {@code schema} ?????????????????? ?? ?????????? {@code parent}. ???????? ?????????? ????????????????
   * ???????????????????? ?? ???? ??????????????????????, ???? ?????????????????????????????? ?????????? ?????????? ?????????? ???????????? ???????????? {@code container}.
   *
   * @param container ??????????, ?? ?????????????? ???????????????????????? ?????????? ??????????, ???????? {@code schema} ???????????????? ??????????????????
   *        ?? {@code parent}
   * @param parent ??????????, ?? ?????????????????? ?????????????? ???????????????????????????? ??????????????????
   * @param schema ??????????, ?????? ?????????????? ???????????????????????????? ?????????????????? ????????????
   * @param baseClassName ?????????????? ?????? ?????? ???????????????????? ????????????. ???????????????? ?????????? ???????? ???????????????????????? ??????????
   *        ???????????????????? ???????????????? {@code Type} ?????? {@code Enum}
   *
   * @return ?????????????????? ??????????
   *
   * @throws JClassAlreadyExistsException ?????????? ???????????????????? ?????????????? ???? ???????????? ???????????????? ?????? ????????????????????
   *         ??????????, ??.??. ?????? ?????????? ?????????? ??????????????????????, ?? ?????????? ????????????????????????, ???????????? ?????? ???????? ????????????????????
   *         ????????????????????
   */
  private AbstractJClass generate(JDefinedClass container, Schema parent, Schema schema, String baseClassName) throws JClassAlreadyExistsException {
    // ???????? ?????????? ?????????????????? ????-??????????, ???? ???????????? ?????????????????? ??????????
    if (schema.getParent() == parent) {
      return generate(container, baseClassName, true, schema);
    }
    // ?????????? ?????????? ?????????????????? ?? ?????????????? "$defs" ?????? "definitions" (?? ?????????????????????? ???? ???????????? ??????????????????)
    // ?????????????? ???????????????????? ???? ???? ?????????????? ????????????
    return generateTopLevel(className(schema), schema);
  }

  /**
   * ??????????????????, ?????? ?????????? ?????????????????? ?? ???????????? ?????????????????????? ???????? ({@code definitions} ?????? {@code $defs})
   * ???????????????? ??????????????????.
   *
   * @param schema ?????????????????????? ??????????
   *
   * @return {@code true}, ???????? ?????????? ?????????????????? ????????, ??, ?????? ??????????????????, ?????????? ?????????? ??????, ??
   *         {@code false} ??????????
   */
  private static boolean isTopLevel(Schema schema) {
    // ?????????????????? ???????? ???? ???????????????? ?????????? ???????????? ?????????????????????????? JSON ??????????????????
    final String local = schema.getUri().getFragment();
    return local.startsWith("/definitions") || local.startsWith("/$defs");
  }
  /**
   * ???????????????????? ?????? ???????????? ???? ???????????????????????????? ??????????.
   * ???? ???????????????????????? ?????? ???????????????? ??????????, ???????????????????????????? ???????? JSON ????????????????, ?? ???????????? ?????? ????????,
   * ?????????????????????? ???????????? ??????????????????. ?????? ?????????????????? ?????????????????? ?????? ?????????????????????? ?????????????????? ??????????????.
   *
   * ???????????????? ?????????????????? ???????????????? ???????? ???? {@link Schema#getUri JSON Pointer} ???? ?????????? ?? ?????????????????? ??
   * ???????????????????????? ?????? ?? CamelCase
   *
   * @param schema
   *
   * @return ???????????????????? Java ?????? ?????? ????????????
   */
  private static String className(Schema schema) {
    // ???????? ???????????????? ???????????? ???????????????? JSON Pointer, ?????? ?????????? ?????????????????? ?????? Path, ??.??. ????????????????????????
    // ???????????????????? ???????????? ?????????? URI ?? ???????????????????? ?????? ????????
    final Path name = Paths.get(schema.getUri().getFragment()).getFileName();
    return toTitleCase(name.toString());
  }
  /**
   * ???????????????????? ?????? ?????????????? ???????????????? ???? ?????????? ???????????????? ?? JSON Schema. ???????????????????????? ?????????????????????? ??????????????
   * Java, ???????????????????????????? ?????????????????? Java Beans: camelCase ?????? ??????????????, ???????????????????? ?? {@code get}
   *
   * @param property ?????? ????????????????, ?????? ?????????????????? ?? JSON Schema
   *
   * @return ???????????????????? ?????? ???????????????? ?????? Java, ?????????????????????????????? ???????????????????????? Java Beans
   */
  private static String getterName(String property) {
    return "get" + toTitleCase(property);
  }
  /**
   * ?????????????????????? ?????????????????? ???????????? ?? {@code TitleCase} ???? {@code snake_case} ?????? {@code kebab-case}.
   * ???????? ???????????? ?????? ?? ?????????? ??????????????, ???? ???????????? ????. ???? ???????????????? ?????????????? ???????????????? ???????????? ?????????? ??????,
   * ?????????????? ?????????????????? ?????????? ?????????????????? {@code "_"} ?????? {@code "-"} ?????? ?? ???????????? ????????????.
   *
   * @param name ?????????????????????????? ???? JSON Schema
   *
   * @return Java ?????????????????????????? ?? {@code TitleCase}
   */
  private static String toTitleCase(String name) {
    final StringBuilder sb = new StringBuilder();
    for (final String part : name.split("[-_ ]")) {
      sb.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
    }
    return sb.toString();
  }
  /**
   * ???????????????????? ??????????, ?????????????? ?????????????? ???????????????????????? ?????? ??????????????????????, ?????????????????????? ?? ?????????????? ????????????????????
   * ???????????????? ?????????? {@code "type"}. ?????????????? ???????? ???? ????????:
   * <table>
   * <tr><th>??????????</th><th>??????????</th></tr>
   * <tr>
   * <td><code><pre>
   * { "type": "boolean" }
   * { "type": ["boolean", "null"] }
   * { "type": ["null", "boolean"] }
   * </pre></code></td>
   * <td>{@link JsonBoolean}</td>
   * </tr>
   * <tr>
   * <td><code><pre>
   * { "type": "string" }
   * { "type": ["string", "null"] }
   * { "type": ["null", "string"] }
   * </pre></code></td>
   * <td>{@link JsonString}</td>
   * </tr>
   * <tr>
   * <td><code><pre>
   * { "type": "number" }
   * { "type": ["number", "null"] }
   * { "type": ["null", "number"] }
   * </pre></code></td>
   * <td>{@link JsonNumber}</td>
   * </tr>
   * <tr>
   * <td><code><pre>
   * { "type": "integer" }
   * { "type": ["integer", "null"] }
   * { "type": ["null", "integer"] }
   * </pre></code></td>
   * <td>{@link JsonInteger}</td>
   * </tr>
   * </table>
   * ?????? ?????????? ???????????????????? ???????????????????????? {@code null}
   *
   * @param type?? ???????????????? ?????????????? {@code "type"} JSON Schema
   *
   * @return ???????? ???? {@link JsonScalar ??????????????????} ??????????, ???? ?????????????????????? {@link JsonNull}
   */
  private static Class<? extends JsonScalar> toScalar(Collection<String> types) {
    Class<? extends JsonScalar> scalar = null;
    for (final String type : types) {
      // null, ?????????????????????? ?? ???????????????????????? ????????, ???? ???????????? ?????? ?????????????????????? ??????????????, ?????????????? ????????????
      // ????????????????????
      if ("null".equals(type)) continue;

      // ???????? ?????? ?????? ????????????, ???? ???? ???? ???????????? (?? ??????????????????) ???????????????? ??????????. ?????? ?????? ???? ?????????? ????????????,
      // ???? ?? ???????????? ???? "null", ?? ?????? ????????????, ?????? ?? ?????? ???????????????? ("boolean", "string", "number" ??????
      // "integer") ???????????? ?? ??????????-???? ???????????? ?????????? -- ???? ???????? ?????? ?? ?????? ?????? "oneOf", ?????????????? ??????????????,
      // ????????????????????????, ?????? ?? ?????? ???? ????????????.
      if (scalar != null) return null;

      // ???????? ?????? ???????????????????????? ???????????????? (???????????? ?????????????????????????? "null"), ???? ?????? ?????????? ?????? ??????
      // ?????????????????????????? ???????????? ??????????
      scalar = typeToScalar(type);
    }
    return scalar;
  }
  /**
   * ???????????????????? ??????????, ?????????????? ?????????????? ???????????????????????? ?????? ??????????????????????, ?????????????????????? ?? ?????????????? ????????????????????
   * ???????????????? ?????????? {@code "type"}. ?????????????? ?????????? JSON Schema ???? ????????????:
   * <table>
   * <tr><th>??????</th><th>??????????</th></tr>
   * <tr>
   * <td>{@code "boolean"}</td>
   * <td>{@link JsonBoolean}</td>
   * </tr>
   * <tr>
   * <td>{@code "string"}</td>
   * <td>{@link JsonString}</td>
   * </tr>
   * <tr>
   * <td>{@code "number"}</td>
   * <td>{@link JsonNumber}</td>
   * </tr>
   * <tr>
   * <td>{@code "integer"}</td>
   * <td>{@link JsonInteger}</td>
   * </tr>
   * </table>
   * ?????? ?????????? ???????????????????? ???????????????????????? {@code null}
   *
   * @param type ???????????????? ?????????????? {@code "type"} JSON Schema
   *
   * @return ???????? ???? {@link JsonScalar ??????????????????} ??????????, ???? ?????????????????????? {@link JsonNull}
   */
  private static Class<? extends JsonScalar> typeToScalar(String type) {
    switch (type) {
      case "boolean": return JsonBoolean.class;
      case "string":  return JsonString.class;
      case "number":  return JsonNumber.class;
      case "integer": return JsonInteger.class;
    }
    return null;
  }
  /**
   * ???????????????????? ?????? ?????? ???????????? ?????? ????????????????????, ?????????????? ?????? ???? ???????????????? ?? ???????????? ????????????. ??????????????????????
   * ???????????? ????????????, ?????????????????????????????? ?????????????????????? ?? ????????????, ???????????????????????????? ????????????????????????, ???? ??????????????????????
   * ???????????? {@link #getClass}.
   *
   * @param clazz ??????????, ?????? ???????????????? ?????????????????? ??????
   * @param baseName ?????????????????? ?????? ?????? ????????????. ???????? ?????? ?????? ?? ?????? ??????????????????, ?????? ?? ???????????????? ?? ????????????????
   *        ?????????? ????????????
   *
   * @return ?????? ?????? ????????????, ?????????????????????????? ???????????????????? ???????????????????? ?? ?????????????????? ????????????
   */
  private static String resolveCollision(JDefinedClass clazz, String baseName) {
    int suffix = 1;
    String name = baseName;
    if ("getClass".equals(baseName)) {
      name = baseName + suffix;
      ++suffix;
    }
    while (clazz.getMethod(name, new AbstractJType[0]) != null) {
      name = baseName + suffix;
      ++suffix;
    }
    return name;
  }
}
