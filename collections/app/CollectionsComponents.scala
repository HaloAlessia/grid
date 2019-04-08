import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import com.gu.mediaservice.lib.aws.{DynamoDB, Kinesis, MessageSender, SNS}
import com.gu.mediaservice.lib.play.{GridAuthentication, GridComponents}
import controllers.{CollectionsController, ImageCollectionsController}
import lib.CollectionsMetrics
import play.api.ApplicationLoader.Context
import router.Routes
import store.CollectionsStore

class CollectionsComponents(context: Context) extends GridComponents(context) with GridAuthentication {
  val dynamoClient = AmazonDynamoDBAsyncClientBuilder.standard().withRegion(region).withCredentials(awsCredentials).build()
  val snsClient = AmazonSNSClientBuilder.standard().withRegion(region).withCredentials(awsCredentials).build()
  val kinesisClient = AmazonKinesisClientBuilder.standard().withRegion(region).withCredentials(awsCredentials).build()
  val cloudwatchClient = AmazonCloudWatchClientBuilder.standard().withRegion(region).withCredentials(awsCredentials).build()

  val collectionsTable = config.get[String]("dynamo.table.collections")
  val imageCollectionsTable = config.get[String]("dynamo.table.imageCollections")
  val snsTopicArn = config.get[String]("sns.topic.arn")
  val thrallKinesisStream = config.get[String]("thrall.kinesis.stream")
  val cloudwatchMetricsNamespace = config.get[String]("cloudwatch.metrics.namespace")

  val sns = new SNS(snsClient, snsTopicArn)
  val kinesis = new Kinesis(kinesisClient, thrallKinesisStream)

  val collectionsStore = new CollectionsStore(new DynamoDB(dynamoClient, collectionsTable))
  val imageCollectionsStore = new DynamoDB(dynamoClient, imageCollectionsTable)
  val metrics = new CollectionsMetrics(cloudwatchMetricsNamespace, cloudwatchClient)
  val notifications = new MessageSender(sns, kinesis)

  val collections = new CollectionsController(auth, services, collectionsStore, controllerComponents)
  val imageCollections = new ImageCollectionsController(auth, imageCollectionsStore, notifications, controllerComponents)

  override val router = new Routes(httpErrorHandler, collections, imageCollections, management)
}
