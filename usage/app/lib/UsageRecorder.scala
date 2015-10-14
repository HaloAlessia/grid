package lib

import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.json._

import rx.lang.scala.Observable

import model._


object UsageRecorder {
  val usageStream = UsageStream.observable
  val observable = usageStream.flatMap(recordUpdates).onErrorReturn(error => {
      Logger.error("UsageRecorder stream encountered an error", error)
      UsageMetrics.incrementErrors

      JsObject(Seq(("error" -> JsString(error.getMessage))))
  })

  // Subscription should not be evaluated until required
  lazy val subscription = UsageRecorder.observable.subscribe(
    (usage: JsObject) => {
      Logger.debug(s"UsageRecorder processed update: ${usage}")
      UsageMetrics.incrementUpdated
    }
  )

  def subscribe = subscription // Helper method to eval subscription
  def unsubscribe = subscription.unsubscribe

  def recordUpdates(usageGroup: UsageGroup) = {
    UsageTable.matchUsageGroup(usageGroup).flatMap(dbUsageGroup => {

      val deletes = (dbUsageGroup.usages -- usageGroup.usages).map(UsageTable.delete(_))
      val creates = (usageGroup.usages -- dbUsageGroup.usages).map(UsageTable.create(_))
      val updates = (usageGroup.usages & dbUsageGroup.usages).map(UsageTable.update(_))

      Observable.from(deletes ++ updates ++ creates).flatten[JsObject]
    })
  }
}
