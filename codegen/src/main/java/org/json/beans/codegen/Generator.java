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
import java.util.List;
import java.util.Map;
import net.jimblackler.jsonschemafriend.Schema;
import net.jimblackler.jsonschemafriend.SchemaStore;

/**
 *
 * @author Mingun
 */
public class Generator {
  /** Генератор Java кода. */
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
   * Генерирует класс, у которого в схеме явно задано собственное имя, либо класс для схемы всего
   * JSON документа. Кроме того, добавляет классам аннотации с информацией о схеме, из которой они
   * были сгенерированы (для внутренних классов эта информация очевидна -- она находится в их корневом
   * охватывающем классе)
   *
   * @param className Имя генерируемого класса
   * @param schema Схема, для которой генерируется класс
   *
   * @return Созданный класс
   *
   * @throws JClassAlreadyExistsException Такое исключение никогда не должно кидаться для корректной
   *         схемы, т.к. все имена будут уникальными, а схема валидируется, прежде чем быть переданной
   *         генератору
   */
  private JDefinedClass generateTopLevel(String className, Schema schema) throws JClassAlreadyExistsException {
    final JDefinedClass clazz = generate(model.rootPackage(), className, false, schema);
    return clazz;
  }
  /**
   * Генерирует класс, представляющий тип, описываемый схемой
   *
   * @param container Объект, внутри которого ненерировать новые классы
   * @param baseClassName Базовое имя для класса. Реальное может быть сформировано путем добавление суффикса
   * @param inner Если класс внутренний, то кего имени добавится суффикс {@code Type} или {@code Enum}.
   *        Внутренние классы используются для схем, не объявленных на верхнем уровне
   * @param schema Схема, для которой генерируется класс
   *
   * @return Созданный класс
   *
   * @throws JClassAlreadyExistsException Такое исключение никогда не должно кидаться для корректной
   *         схемы, т.к. все имена будут уникальными, а схема валидируется, прежде чем быть переданной
   *         генератору
   */
  private JDefinedClass generate(IJClassContainer<JDefinedClass> container, String baseClassName, boolean inner, Schema schema) throws JClassAlreadyExistsException {
    final List<Object> enums = schema.getEnums();

    final JDefinedClass clazz = container._class(
        JMod.PUBLIC,
        inner ? baseClassName + (enums != null ? "Enum" : "Type") : baseClassName,
        enums != null ? EClassType.ENUM : EClassType.CLASS
    );

    // Обходим все пары "название свойства" -> "схема валидации" и генерируем методы доступа.
    for (final Map.Entry<String, Schema> e : schema.getProperties().entrySet()) {
      final String prop = e.getKey();
      final Schema s = e.getValue();
      final JDefinedClass cls;
      // Если схема объявлена по-месту, то делаем вложенный класс
      if (s.getParent() == schema) {
        cls = generate(clazz, toTitleCase(prop), true, s);
      } else {
        // Иначе схема объявлена в разделе "$defs" или "definitions" (в зависимости от версии стандарта)
        // поэтому генерируем ее на верхнем уровне
        cls = generateTopLevel(className(s), s);
      }

      clazz.method(JMod.PUBLIC, cls, getterName(prop));
    }

    return clazz;
  }

  /**
   * Проверяет, что схема объявлена в списке определений схем ({@code definitions} или {@code $defs})
   * текущего документа.
   *
   * @param schema Проверяемая схема
   *
   * @return {@code true}, если схема объявлена явно, и, как следствие, имеет явное имя, и
   *         {@code false} иначе
   */
  private static boolean isTopLevel(Schema schema) {
    // Локальный путь до описания схемы внутри определяющего JSON документа
    final String local = schema.getUri().getFragment();
    return local.startsWith("/definitions") || local.startsWith("/$defs");
  }
  /**
   * Генерирует имя класса из идентификатора схемы.
   * Не предназначен для корневой схемы, представляющей весь JSON документ, а только для схем,
   * объявленных внутри документа. Для корневого документа имя формируется отдельным образом.
   *
   * Получает последний фрагмент пути из {@link Schema#getUri JSON Pointer} на схему в документе и
   * конвертирует его в CamelCase
   *
   * @param schema
   *
   * @return Корректное Java имя для класса
   */
  private static String className(Schema schema) {
    // Хотя фрагмент должен являться JSON Pointer, его можно разобрать как Path, т.к. используемая
    // библиотека только такие URI и генерирует для схем
    final Path name = Paths.get(schema.getUri().getFragment()).getFileName();
    return toTitleCase(name.toString());
  }
  /**
   * Генерирует имя геттера свойства из имени свойства в JSON Schema. Используется стандартная нотация
   * Java, соответсвующая стандарту Java Beans: camelCase для свойств, начинается с {@code get}
   *
   * @param property Имя свойства, как объявлено в JSON Schema
   *
   * @return Корректное имя свойства для Java, соответствующее спецификации Java Beans
   */
  private static String getterName(String property) {
    return "get" + toTitleCase(property);
  }
  /**
   * Перобразует указанную строку в {@code TitleCase} из {@code snake_case} или {@code kebab-case}.
   * Если строка уже в таком формате, не меняет ее. Не изменяет регистр символов строки кроме тех,
   * которые находятся перед символами {@code "_"} или {@code "-"} или в начале строки.
   *
   * @param name Идентификатор из JSON Schema
   *
   * @return Java идентификатор в {@code TitleCase}
   */
  private static String toTitleCase(String name) {
    final StringBuilder sb = new StringBuilder();
    for (final String part : name.split("[-_ ]")) {
      sb.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
    }
    return sb.toString();
  }
}
