package com.gu.mediaservice.model

import com.gu.mediaservice.model.FileMetadata.readStringOrListHeadProp
import net.logstash.logback.marker.Markers
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

case class KvPair(key: String, values: JsValue)

case class FileMetadata(
                         iptc: Map[String, String] = Map(),
                         exif: Map[String, String] = Map(),
                         exifSub: Map[String, String] = Map(),
                         xmp: Seq[KvPair] =Seq(),
                         icc: Map[String, String] = Map(),
                         getty: Map[String, String] = Map(),
                         colourModel: Option[String] = None,
                         colourModelInformation: Map[String, String] = Map()
                       ) {
  def toLogMarker = {
    val fieldCountMarkers = Map(
      "iptcFieldCount" -> iptc.size,
      "exifFieldCount" -> exif.size,
      "exifSubFieldCount" -> exifSub.size,
      "xmpFieldCount" -> xmp.size,
      "iccFieldCount" -> icc.size,
      "gettyFieldCount" -> getty.size,
      "colourModelInformationFieldCount" -> colourModelInformation.size
    )

    val totalFieldCount = fieldCountMarkers.foldLeft(0)(_ + _._2)
    val markers = fieldCountMarkers + ("totalFieldCount" -> totalFieldCount)

    Markers.appendEntries(markers.asJava)
  }

  def readXmpProp(name: String): Option[String] = readStringOrListHeadProp(name, this.xmp)
}

object FileMetadata {
  // TODO: reindex all images to make the getty map always present
  // for data consistency, so we can fallback to use the default Reads

  trait JsType
  case object JArr extends JsType
  case object JStr extends JsType
  case object JObj extends JsType

  def aggregateMetadataMap(initialMap: Map[String, String]): Seq[KvPair] = {

    val getNormalisedKeyAndValType: String => (String, String, JsType) = (k: String) => {
      val isArrayKey = k.endsWith("]")
      val isSimpleDynamicObject = k.contains("/")
      val res = if (isArrayKey) {
        val keyFromArr = k.substring(0, k.lastIndexOf("["))
        (keyFromArr, "", JArr)
      } else if (isSimpleDynamicObject){
        val sIdx = k.lastIndexOf("/")
        val l = k.substring(0, sIdx)
        val r = k.substring(sIdx+1)
        (l, r, JObj)
      } else {
        (k, "", JStr)
      }
      res
    }

    val mutableMap = scala.collection.mutable.Map[String, (JsType, ArrayBuffer[String])]()

    for (originalKey <- initialMap.keySet) {
      val value = initialMap(originalKey)
      val (normalisedKey, rest, typ) = getNormalisedKeyAndValType(originalKey)
      if (mutableMap.contains(normalisedKey)) {
        if (rest.nonEmpty) mutableMap(normalisedKey)._2 += rest
        mutableMap(normalisedKey)._2 += value
      } else {
        if (rest.nonEmpty) {
          mutableMap.put(normalisedKey, (typ, ArrayBuffer(rest, value)))
        } else {
          mutableMap.put(normalisedKey, (typ, ArrayBuffer(value)))
        }
      }
    }

    val normalisedMap: Seq[KvPair] = mutableMap.map {
      case (k, v) =>
        val props = v._2
        v._1 match {
          case JObj =>
            val tups: Seq[Seq[String]] = for(i <- props.indices by 2) yield Seq(props(i), props(i+1))
            val t2: Seq[JsValue] = tups.map(el => JsArray(el.map(JsString)))
//            val ob = JsArray(props.map(JsString))
//            val tuuups: Seq[KvPair] = Seq(KvPair("key", JsString(k)), KvPair("values", ob))
//            val sec = JsObject(tuuups)
            KvPair(k, JsArray(t2))
          case JArr | JStr =>
            val value = if (props.size > 1) JsArray(props.map(JsString)) else JsString(props.head)
//            val sec = JsObject(tuuups)
              KvPair(k, value)

          case _ =>
            KvPair(k, JsString("test"))
        }
    }.toSeq

//    normalisedMap.foreach{
//      case (k, v) => println(s"$k ---> $v")
//    }

//    normalisedMap.foreach{
//      case (k, v) => println(s"$k ---> $v")
//    }

    normalisedMap
  }

//  def rollUpAllMetadata(metaMap: Map[String, JsValue]) = {
//
//    val aggMutableMap = scala.collection.mutable.Map[String, ArrayBuffer[JsValue]]()
//
//    metaMap.map{
//      case (k, v) =>
//        val isArrayKey = k.endsWith("]")
//        val keyFromArr = k.substring(0, k.lastIndexOf("["))
//        val lisofjsvals = scala.collection.mutable.ArrayBuffer[JsValue]()
//        if (isArrayKey){
//          v match {
//            case JsString(value) =>
//            case JsObject(value) =>
//            case JsArray(value) =>
//          }
//        }
//    }
//
//  }

  def readStringOrListHeadProp(name: String, tups: Seq[KvPair]): Option[String] = {
    println("")
    val genericMap = tups.map(t => {
      (t.key, t.values)
    }).toMap
    genericMap.get(name).map(prop => {
      prop match {
        case JsString(v) => v
        case JsArray(v) => v.head.toString
        case _ => throw new Exception("sdfsdf")
      }
    })
  }

}

object FileMetadataFormatters {

  private val maximumValueLengthBytes = 5000

  private def removeLongValues = { m: Map[String, String] => {
    val (short, long) = m.partition(_._2.length <= maximumValueLengthBytes)
    if (long.size > 0) {
      short + ("removedFields" -> long.map(_._1).mkString(", "))
    } else {
      m
    }
  }
  }

  implicit val KvPairFormatter = Json.format[KvPair]

  implicit val ImageMetadataFormatter: Reads[FileMetadata] = (
    (__ \ "iptc").read[Map[String, String]] ~
      (__ \ "exif").read[Map[String, String]] ~
      (__ \ "exifSub").read[Map[String, String]] ~
      (__ \ "xmp").read[Seq[KvPair]] ~
      (__ \ "icc").readNullable[Map[String, String]].map(_ getOrElse Map()).map(removeLongValues) ~
      (__ \ "getty").readNullable[Map[String, String]].map(_ getOrElse Map()) ~
      (__ \ "colourModel").readNullable[String] ~
      (__ \ "colourModelInformation").readNullable[Map[String, String]].map(_ getOrElse Map())

    ) (FileMetadata.apply _)

  implicit val FileMetadataWrites: Writes[FileMetadata] = (
    (JsPath \ "iptc").write[Map[String, String]] and
      (JsPath \ "exif").write[Map[String, String]] and
      (JsPath \ "exifSub").write[Map[String, String]] and
      (JsPath \ "xmp").write[Seq[KvPair]] and
      (JsPath \ "icc").write[Map[String, String]].contramap[Map[String, String]](removeLongValues) and
      (JsPath \ "getty").write[Map[String, String]] and
      (JsPath \ "colourModel").writeNullable[String] and
      (JsPath \ "colourModelInformation").write[Map[String, String]]
    ) (unlift(FileMetadata.unapply))
}
