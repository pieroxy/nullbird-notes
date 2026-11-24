package net.pieroxy.notes.api;

import com.google.gson.Gson;
import net.pieroxy.notes.api.metadata.BaseApiEndpoint;
import net.pieroxy.notes.api.metadata.ApiEndpoint;
import net.pieroxy.notes.api.metadata.Endpoint;
import net.pieroxy.notes.api.metadata.RequiredFields;
import net.pieroxy.notes.api.metadata.TypeScriptType;
import net.pieroxy.notes.sync.Merge3;
import net.pieroxy.notes.utils.BuildInfo;
import net.pieroxy.notes.utils.reflection.GenericTypeArgumentResolver;
import net.pieroxy.notes.utils.reflection.GetAccessibleClasses;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Run by the {@code generate-api-ts-stubs} step in {@code pom.xml} (process-classes phase); writes the .ts path passed as args[0]. */
public class GenerateTsStubs {
  private static BufferedWriter writer;
  private static final List<String> later = new ArrayList<>();

  private static void write(String s) throws IOException {
    writer.write(s);
    writer.newLine();
  }

  private static void writeLater(String s) {
    later.add(s);
  }

  public static void main(String[] args) throws Exception {
    List<Class<?>> dtos = GetAccessibleClasses.getClasses("net.pieroxy.notes").stream()
        .filter(c -> c.isAnnotationPresent(TypeScriptType.class)).toList();
    List<String> errors = RequiredFields.findOptionalPrimitives(dtos);
    if (!errors.isEmpty()) {
      errors.forEach(System.err::println);
      throw new IllegalStateException(errors.size() + " DTO field(s) break the @Required rule, see above.");
    }

    File out = new File(args[0]);
    try (BufferedWriter build = new BufferedWriter(new FileWriter(new File(out.getParentFile(), "BuildId.ts")))) {
      build.write("// Generated: this build's identity, sent with every API call (see ApiServlet).");
      build.newLine();
      build.write("export const BUILD_ID = \"" + BuildInfo.ID + "\";");
      build.newLine();
    }
    try (BufferedWriter constants = new BufferedWriter(new FileWriter(new File(out.getParentFile(), "Constants.ts")))) {
      constants.write("// Generated: the server's constants the web app needs.");
      constants.newLine();
      constants.write("/** What starts the line Merge3 puts above each clash it couldn't settle. */");
      constants.newLine();
      constants.write("export const MERGE_TAG = " + new Gson().toJson(Merge3.TAG) + ";");
      constants.newLine();
    }
    writer = new BufferedWriter(new FileWriter(out));

    write("/* tslint:disable */");
    write("/* eslint-disable */");
    write("// Generated on " + new Date());
    write("import { Api, CallOptions } from \"../utils/api/Api\";");
    writeLater("export class ApiEndpoints {");

    Set<String> toImport = new HashSet<>();
    List<Class<?>> allClasses = GetAccessibleClasses.getClasses("net.pieroxy.notes");
    for (Class<?> c : allClasses) {
      if (ApiEndpoint.class.isAssignableFrom(c) && c.isAnnotationPresent(Endpoint.class)) {
        System.out.println("Processing " + c.getName());
        Endpoint endpoint = c.getAnnotation(Endpoint.class);
        String input = getType(c, 0);
        String output = getType(c, 1);
        String name = c.getSimpleName().substring(0, c.getSimpleName().length() - "Api".length());
        if (!input.equals("any")) toImport.add(input);
        if (!output.equals("any")) toImport.add(output);

        writeLater("    public static " + name + " = {");
        writeLater("        call:(input:" + input + ", options?:CallOptions):Promise<" + output + "> => {");
        writeLater("            return Api.call<" + input + ", " + output + ">({");
        writeLater("                    method:\"" + endpoint.method() + "\",");
        writeLater("                    body:input,");
        writeLater("                    endpoint:\"" + name + "\",");
        writeLater("                    redraw:" + endpoint.redraw() + ",");
        writeLater("                    signal:options?.signal");
        writeLater("            });");
        writeLater("        }");
        writeLater("    }");
        writeLater("");
      }
    }

    write("import { " + toImport.stream().collect(Collectors.joining(",")) + " } from \"./api-types\";");
    for (String s : later) write(s);
    write("}");

    writer.close();
  }

  private static String getType(Class<?> leafClass, int argIndex) {
    Class<?> clazz = GenericTypeArgumentResolver.resolve(leafClass, BaseApiEndpoint.class, argIndex);
    String res;
    if (clazz == Object.class) {
      res = "any";
    } else {
      res = clazz.getSimpleName();
      if (clazz.getAnnotation(TypeScriptType.class) == null)
        throw new RuntimeException(leafClass.getName() + " uses parameter that is not a TypeScriptType");
    }
    return res;
  }
}
