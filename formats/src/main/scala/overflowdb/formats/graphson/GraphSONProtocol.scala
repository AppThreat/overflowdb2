package overflowdb.formats.graphson
import spray.json.*

object GraphSONProtocol extends DefaultJsonProtocol:

    implicit object PropertyValueJsonFormat extends RootJsonFormat[PropertyValue]:
        def write(c: PropertyValue): JsValue =
            c match
                case x: StringValue  => JsString(x.`@value`)
                case x: BooleanValue => JsBoolean(x.`@value`)
                case x: ListValue =>
                    JsObject(
                      "@value" -> JsArray(x.`@value`.map(write).toVector),
                      "@type"  -> JsString(x.`@type`)
                    )
                case x: LongValue =>
                    JsObject(
                      "@value" -> JsNumber(x.`@value`),
                      "@type"  -> JsString(x.`@type`)
                    )
                case x: IntValue =>
                    JsObject(
                      "@value" -> JsNumber(x.`@value`),
                      "@type"  -> JsString(x.`@type`)
                    )
                case x: FloatValue =>
                    JsObject(
                      "@value" -> JsNumber(x.`@value`),
                      "@type"  -> JsString(x.`@type`)
                    )
                case x: DoubleValue =>
                    JsObject(
                      "@value" -> JsNumber(x.`@value`),
                      "@type"  -> JsString(x.`@type`)
                    )
                case x: NodeIdValue =>
                    JsObject(
                      "@value" -> JsNumber(x.`@value`),
                      "@type"  -> JsString(x.`@type`)
                    )
                case x => serializationError(s"unsupported propertyValue: $x")

        def read(value: JsValue): PropertyValue & Product =
            value match
                case JsString(v)  => return StringValue(v)
                case JsBoolean(v) => return BooleanValue(v)
                case _            =>
            value.asJsObject.getFields("@value", "@type") match
                case Seq(JsArray(v), JsString(_)) => ListValue(v.map(read).toArray)
                case x: Seq[?]                    => readNonList(x)
                case null                         => deserializationError("PropertyValue expected")

        def readNonList(value: Seq[?]): PropertyValue & Product = value match
            case Seq(JsNumber(v), JsString(typ)) =>
                if typ.equals(Type.Long.typ) then LongValue(v.toLongExact)
                else if typ.equals(Type.Int.typ) then IntValue(v.toIntExact)
                else if typ.equals(Type.Float.typ) then FloatValue(v.toFloat)
                else if typ.equals(Type.Double.typ) then DoubleValue(v.toDouble)
                else if typ.equals(Type.NodeId.typ) then NodeIdValue(v.toLongExact)
                else deserializationError("Valid number type or list expected")
            case _ => deserializationError("PropertyValue expected")
    end PropertyValueJsonFormat

    implicit object LongValueFormat extends RootJsonFormat[LongValue]:
        def write(c: LongValue): JsValue = PropertyValueJsonFormat.write(c)

        def read(value: JsValue): LongValue & Product =
            value.asJsObject.getFields("@value", "@type") match
                case Seq(JsNumber(v), JsString(typ)) if typ.equals(Type.Long.typ) =>
                    LongValue(v.toLongExact)
                case _ => deserializationError("LongValue expected")

    implicit val propertyFormat: RootJsonFormat[Property] =
        jsonFormat3(Property.apply)

    implicit val vertexFormat: RootJsonFormat[Vertex] =
        jsonFormat4(Vertex.apply)

    implicit val edgeFormat: RootJsonFormat[Edge] =
        jsonFormat8(Edge.apply)

    implicit val graphSONElementsFormat: RootJsonFormat[GraphSONElements] =
        jsonFormat2(GraphSONElements.apply)

    implicit val graphSONFormat: RootJsonFormat[GraphSON] =
        jsonFormat2(GraphSON.apply)
end GraphSONProtocol
