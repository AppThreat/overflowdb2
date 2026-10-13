package object overflowdb:
    implicit def toPropertyKeyOps[A](propertyKey: PropertyKey[A]): PropertyKeyOps[A] =
        new PropertyKeyOps[A](propertyKey)
    implicit def toGraphSugar(graph: Graph): GraphSugar = new GraphSugar(graph)
    implicit def toNodeSugar(node: Node): NodeSugar     = new NodeSugar(node)

    /* Since Scala 3.10, Predef's `->` is an extension method rather than the implicit class
     * ArrowAssoc. Extension methods are tried before implicit conversions, so `key -> value` would
     * build a tuple and never reach PropertyKeyOps. These two overloads take its place wherever
     * this package is in scope: the more specific one keeps `key -> value` a Property, the other
     * builds the same tuple as Predef's for everything else. */
    extension [A](propertyKey: PropertyKey[A])
        def ->(value: A): Property[A] = propertyKey.of(value)

    extension [A](self: A)
        inline def ->[B](inline that: B): (A, B) = (self, that)
