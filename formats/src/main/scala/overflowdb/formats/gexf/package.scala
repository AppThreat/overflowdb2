package overflowdb.formats

package object gexf:
    object Type extends Enumeration:
        val Boolean = Value("boolean")
        val Integer = Value("integer")
        val Long    = Value("long")
        val Float   = Value("float")
        val Double  = Value("double")
        val String  = Value("string")

        def fromRuntimeClass(clazz: Class[?]): Type.Value =
            if clazz.isAssignableFrom(classOf[Boolean]) || clazz.isAssignableFrom(
                  classOf[java.lang.Boolean]
                )
            then
                Type.Boolean
            else if clazz.isAssignableFrom(classOf[Int]) || clazz.isAssignableFrom(
                  classOf[Integer]
                ) ||
                clazz.isAssignableFrom(classOf[Byte]) || clazz.isAssignableFrom(
                  classOf[java.lang.Byte]
                ) ||
                clazz.isAssignableFrom(classOf[Short]) || clazz.isAssignableFrom(
                  classOf[java.lang.Short]
                )
            then
                Type.Integer
            else if clazz.isAssignableFrom(classOf[Long]) || clazz.isAssignableFrom(
                  classOf[java.lang.Long]
                )
            then
                Type.Long
            else if clazz.isAssignableFrom(classOf[Float]) || clazz.isAssignableFrom(
                  classOf[java.lang.Float]
                )
            then
                Type.Float
            else if clazz.isAssignableFrom(classOf[Double]) || clazz.isAssignableFrom(
                  classOf[java.lang.Double]
                )
            then
                Type.Double
            else if clazz.isAssignableFrom(classOf[String]) then
                Type.String
            else
                Type.String // Fallback to string representation for complex/unhandled types
    end Type

    private[gexf] case class PropertyContext(name: String, tpe: Type.Value)
end gexf
