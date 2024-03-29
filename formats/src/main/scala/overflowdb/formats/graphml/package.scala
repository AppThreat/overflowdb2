package overflowdb.formats

package object graphml:
    // we could/should make these configurable...
    val KeyForNodeLabel = "labelV"
    val KeyForEdgeLabel = "labelE"

    object Type extends Enumeration:
        val Boolean = Value("boolean")
        val Int     = Value("int")
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
            else if clazz.isAssignableFrom(classOf[Int]) || clazz.isAssignableFrom(classOf[Integer])
            then
                Type.Int
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
                throw new AssertionError(
                  s"unsupported runtime class `$clazz` - only ${Type.values.mkString("|")} are supported...}"
                )
    end Type

    private[graphml] case class PropertyContext(name: String, tpe: Type.Value)
end graphml
