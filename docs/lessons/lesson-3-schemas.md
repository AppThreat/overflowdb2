# Lesson 3: Strongly-Typed Schema Definition and Codegen

### Learning Objective

Learn how schemas define strongly-typed nodes, edges, properties, and constraints, and understand how the schema compiler generates concrete Java/Scala domain classes.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Access to CPG2 Schemas**: Review [cpg2 schema source files](https://github.com/AppThreat/cpg2/tree/main/schema) to understand concrete implementations.

### Conceptual Background

Generic graph databases model everything using key-value maps on unstructured nodes and edges. While flexible, this introduces memory bloat and runtime schema violations. OverflowDB uses schema-driven domain classes to enforce correctness and memory density.

Developers write program schemas using a DSL. The schema describes:

- **Properties**: Key definitions, value types, and default values.
- **Node Types**: Names, description, allowed properties, and edge connection rules.
- **Edge Types**: Labels and allowed property mappings.

During the build process, a schema compiler processes these definitions and generates Java and Scala classes:

- **Node/Edge Factories**: Concrete implementations of `NodeFactory` and `EdgeFactory` used to instantiate elements dynamically.
- **Ref/Db Layout Classes**: Subclasses of `NodeRef` and `NodeDb`. The generated `NodeDb` classes contain fields for properties and edge layouts, eliminating generic map overhead.
- **AST/CFG Shortcuts**: Traversal helper classes for quick navigation.

This code generation ensures compile-time type safety: you cannot connect nodes via invalid edge types or set properties that violate the schema definition.

### Real Commands

Build a project containing schema definitions to generate the concrete domain classes:

```bash
sbt compile
```

### Code Example

The generated `NodeDb` subclass represents property storage as fields, avoiding map storage. Below is a conceptual illustration of a generated class:

```java
public class MethodDb extends NodeDb {
  private String name;
  private String fullName;
  private String signature;

  @Override
  public Object property(String key) {
    switch(key) {
      case "NAME": return name;
      case "FULL_NAME": return fullName;
      case "SIGNATURE": return signature;
      default: return null;
    }
  }

  @Override
  public void setProperty(String key, Object value) {
    switch(key) {
      case "NAME": this.name = (String) value; break;
      case "FULL_NAME": this.fullName = (String) value; break;
      case "SIGNATURE": this.signature = (String) value; break;
      default: throw new SchemaViolationException(key);
    }
  }
}
```
