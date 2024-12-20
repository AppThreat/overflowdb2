package overflowdb.formats.neo4jcsv

import overflowdb.formats.iterableForList

import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.jdk.CollectionConverters.IterableHasAsScala

sealed trait ColumnDef
case class ScalarColumnDef(valueType: ColumnType.Value) extends ColumnDef
case class ArrayColumnDef(valueType: Option[ColumnType.Value], iteratorAccessor: Any => Iterable[?])
    extends ColumnDef

class ColumnDefinitions(propertyNames: Iterable[String]):
    private val propertyNamesOrdered     = propertyNames.toSeq.sorted
    private val _columnDefByPropertyName = mutable.Map.empty[String, ColumnDef]

    def columnDefByPropertyName(name: String): Option[ColumnDef] =
        _columnDefByPropertyName.get(name)

    def updateWith(propertyName: String, value: Any): ColumnDef =
        _columnDefByPropertyName
            .updateWith(propertyName) {
                case None =>
                    // we didn't see this property before - try to derive it's type from the runtime class
                    Option(deriveNeo4jType(value))
                case Some(ArrayColumnDef(None, _)) =>
                    // value is an array that we've seen before, but we don't have the valueType yet, most likely because previous occurrences were empty arrays
                    Option(deriveNeo4jType(value))
                case completeDef =>
                    completeDef // we already have everything we need, no need to change anything
            }
            .get

    /** for header file */
    def propertiesWithTypes: Seq[String] =
        propertyNamesOrdered.map { name =>
            columnDefByPropertyName(name) match
                case Some(ScalarColumnDef(valueType)) =>
                    s"$name:$valueType"
                case Some(ArrayColumnDef(Some(valueType), _)) =>
                    s"$name:$valueType[]"
                case _ =>
                    name
        }

    /** for data file updates our internal `_columnDefByPropertyName` model with type information
      * based on runtime values, so that we later have all metadata required for the header file
      */
    def propertyValues(byNameAccessor: String => Option[?]): Seq[String] =
        propertyNamesOrdered.map { propertyName =>
            byNameAccessor(propertyName) match
                case None =>
                    "" // property value empty for this element
                case Some(value) =>
                    updateWith(propertyName, value) match
                        case ScalarColumnDef(_) =>
                            value.toString // scalar property value
                        case ArrayColumnDef(_, iteratorAccessor) =>
                            /** Array property value - separated by `;` according to the spec
                              *
                              * Note: if all instances of this array property type are empty, we
                              * will not have the valueType (because it's derived from the runtime
                              * class). At the same time, it doesn't matter for serialization,
                              * because the csv entry is always empty for all empty arrays.
                              */
                            iteratorAccessor(value).mkString(";")
        }

    /** for cypher file <rant> why does neo4j have 4 different ways to import a CSV, out of which
      * only one works, and really the only help we get is a csv file reader, and we need to specify
      * exactly how each column needs to be parsed and mapped...? </rant>
      */
    def propertiesMappingsForCypher(startIndex: Int): Seq[String] =
        var idx = startIndex - 1
        propertyNamesOrdered.map { name =>
            idx += 1
            val accessor = s"line[$idx]"
            columnDefByPropertyName(name) match
                case Some(ScalarColumnDef(columnType)) =>
                    val adaptedAccessor =
                        cypherScalarConversionFunctionMaybe(columnType)
                            .map(f => s"$f($accessor)")
                            .getOrElse(accessor)
                    s"$name: $adaptedAccessor"
                case Some(ArrayColumnDef(columnType, _)) =>
                    val accessor = s"""split(line[$idx], ";")"""
                    val adaptedAccessor =
                        columnType
                            .flatMap(cypherListConversionFunctionMaybe)
                            .map(f => s"$f($accessor)")
                            .getOrElse(accessor)
                    s"$name: $adaptedAccessor"
                case None =>
                    s"$name: $accessor"
        }
    end propertiesMappingsForCypher

    /** optionally choose one of https://neo4j.com/docs/cypher-manual/current/functions/scalar/,
      * depending on the columnType
      */
    private def cypherScalarConversionFunctionMaybe(columnType: ColumnType.Value): Option[String] =
        columnType match
            case ColumnType.Id | ColumnType.Int | ColumnType.Long | ColumnType.Byte | ColumnType.Short =>
                Some("toInteger")
            case ColumnType.Float | ColumnType.Double =>
                Some("toFloat")
            case ColumnType.Boolean =>
                Some("toBoolean")
            case _ => None

    /** optionally choose one of
      * https://neo4j.com/docs/cypher-manual/current/functions/list/#functions-tobooleanlist,
      * depending on the columnType
      */
    private def cypherListConversionFunctionMaybe(columnType: ColumnType.Value): Option[String] =
        columnType match
            case ColumnType.Id | ColumnType.Int | ColumnType.Long | ColumnType.Byte | ColumnType.Short =>
                Some("toIntegerList")
            case ColumnType.Float | ColumnType.Double =>
                Some("toFloatList")
            case ColumnType.Boolean =>
                Some("toBooleanList")
            case ColumnType.String =>
                Some("toStringList")
            case _ => None

    /** derive property types based on the runtime class note: this ignores the edge case that there
      * may be different runtime types for the same property
      */
    private def deriveNeo4jType(value: Any): ColumnDef =
        def deriveNeo4jTypeForArray(iteratorAccessor: Any => Iterable[?]): ArrayColumnDef =
            // Iterable is immutable, so we can safely (try to) get it's first element
            val valueTypeMaybe = iteratorAccessor(value).iterator
                .nextOption()
                .map(_.getClass)
                .map(deriveNeo4jTypeForScalarValue)
            ArrayColumnDef(valueTypeMaybe, iteratorAccessor)

        value match
            case _: Iterable[?] =>
                deriveNeo4jTypeForArray(_.asInstanceOf[Iterable[?]])
            case _: IterableOnce[?] =>
                deriveNeo4jTypeForArray(_.asInstanceOf[IterableOnce[?]].iterator.toSeq)
            case _: java.lang.Iterable[?] =>
                deriveNeo4jTypeForArray(_.asInstanceOf[java.lang.Iterable[?]].asScala)
            case _: Array[?] =>
                deriveNeo4jTypeForArray(x => ArraySeq.unsafeWrapArray(x.asInstanceOf[Array[?]]))
            case scalarValue =>
                ScalarColumnDef(deriveNeo4jTypeForScalarValue(scalarValue.getClass))
    end deriveNeo4jType

    private def deriveNeo4jTypeForScalarValue(tpe: Class[?]): ColumnType.Value =
        if tpe.isAssignableFrom(classOf[String]) then
            ColumnType.String
        else if tpe.isAssignableFrom(classOf[Int]) || tpe.isAssignableFrom(classOf[Integer]) then
            ColumnType.Int
        else if tpe.isAssignableFrom(classOf[Long]) || tpe.isAssignableFrom(classOf[java.lang.Long])
        then
            ColumnType.Long
        else if tpe.isAssignableFrom(classOf[Float]) || tpe.isAssignableFrom(
              classOf[java.lang.Float]
            )
        then
            ColumnType.Float
        else if tpe.isAssignableFrom(classOf[Double]) || tpe.isAssignableFrom(
              classOf[java.lang.Double]
            )
        then
            ColumnType.Double
        else if tpe.isAssignableFrom(classOf[Boolean]) || tpe.isAssignableFrom(
              classOf[java.lang.Boolean]
            )
        then
            ColumnType.Boolean
        else if tpe.isAssignableFrom(classOf[Byte]) || tpe.isAssignableFrom(classOf[java.lang.Byte])
        then
            ColumnType.Byte
        else if tpe.isAssignableFrom(classOf[Short]) || tpe.isAssignableFrom(
              classOf[java.lang.Short]
            )
        then
            ColumnType.Short
        else if tpe.isAssignableFrom(classOf[Char]) then
            ColumnType.Char
        else
            throw new NotImplementedError(
              s"unable to derive a Neo4j type for given runtime type $tpe"
            )
end ColumnDefinitions
