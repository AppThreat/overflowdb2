# Lesson 2: Lazy Monadic Graph Traversals

### Learning Objective

Implement lazy, monadic graph queries using the OverflowDB traversal engine, write custom step operators using Scala implicits, and configure loop behaviour for traversing recursive structures.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Scala Knowledge**: Basic understanding of functional iterators, implicit classes, and traits.

### Conceptual Background

Traversals in code property graphs need to be lazy and evaluate on demand. Standard eager lists generate huge memory consumption when dealing with deep graphs. OverflowDB provides the **[Traversal](https://github.com/AppThreat/overflowdb2/blob/main/traversal/src/main/scala/overflowdb/traversal/Traversal.scala)** class, which extends the standard Scala `Iterator`. This ensures traversals consume minimal memory and evaluate elements one at a time.

Key features of the traversal engine include:

- **Lazy Monadic Pipeline**: Chaining steps via `.map`, `.flatMap`, and `.filter` does not evaluate the graph. Evaluation is triggered only by collector methods like `.l` (returns a List) or `.toSet`.
- **Efficient Loops**: The `.repeat(...)` step handles graph recursion (such as traversing parent AST nodes or child CFG blocks). The loop behaviors are optimized via [RepeatStep](https://github.com/AppThreat/overflowdb2/blob/main/traversal/src/main/scala/overflowdb/traversal/RepeatStep.scala) and [RepeatBehaviour](https://github.com/AppThreat/overflowdb2/blob/main/traversal/src/main/scala/overflowdb/traversal/RepeatBehaviour.scala) to track execution depth and prevent loops.
- **Step Implicits**: Custom query steps can be added dynamically using Scala's implicit wrapper classes.

### Real Commands

Run a basic traversal to find all methods starting with "test", fetch their parameter names, and collect them to a list:

```scala
val testParams: List[String] = cpg.method
  .name("test.*")
  .parameter
  .name
  .l
```

Run a recursive repeat traversal to find all AST children up to depth 5:

```scala
val astChildren: List[AstNode] = node.repeat(_.astChildren)(_.maxDepth(5)).l
```

### Code Example

Custom steps are defined by wrapping the [Traversal](https://github.com/AppThreat/overflowdb2/blob/main/traversal/src/main/scala/overflowdb/traversal/Traversal.scala) class. Below is an example of creating a custom traversal step:

```scala
package overflowdb.traversal

implicit class MethodTraversalExt(val trav: Traversal[Method]) extends AnyVal {
  // Filter methods by name pattern matching
  def hasName(regex: String): Traversal[Method] = {
    trav.filter(m => m.name.matches(regex))
  }

  // Follow the CALL edge to fetch callers
  def callers: Traversal[Method] = {
    trav.flatMap(m => m.callIn.method)
  }
}
```
