package com.microsoft.partnercatalyst.fortis.spark

import com.microsoft.partnercatalyst.fortis.spark.analyzer._
import com.microsoft.partnercatalyst.fortis.spark.dba.CassandraConfigurationManager
import com.microsoft.partnercatalyst.fortis.spark.logging.{AppInsights, Loggable}
import com.microsoft.partnercatalyst.fortis.spark.sources.StreamProviderFactory
import com.microsoft.partnercatalyst.fortis.spark.transformcontext.TransformContextProvider
import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.client.FeatureServiceClient
import org.apache.log4j.{Level, Logger}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import com.microsoft.partnercatalyst.fortis.spark.sinks.cassandra.{CassandraConfig, CassandraEventsSink}
import org.apache.spark.sql.SparkSession

import scala.reflect.runtime.universe.TypeTag
import scala.util.Properties.{envOrElse, envOrNone}

object ProjectFortis extends App with Loggable {
  private implicit val fortisSettings: FortisSettings = {
    def envOrFail(name: String): String = {
      envOrNone(name) match {
        case Some(v) => v
        case None =>
          sys.error(s"Environment variable not defined: $name")
      }
    }

    FortisSettings(
      // Required
      progressDir = envOrFail(Constants.Env.HighlyAvailableProgressDir),
      featureServiceUrlBase = envOrFail(Constants.Env.FeatureServiceUrlBase),
      cassandraHosts = envOrFail(Constants.Env.CassandraHost),
      contextStopWaitTimeMillis = envOrElse(Constants.Env.ContextStopWaitTimeMillis, Constants.ContextStopWaitTimeMillis.toString).toLong,
      managementBusConnectionString = envOrFail(Constants.Env.ManagementBusConnectionString),
      managementBusConfigQueueName = envOrFail(Constants.Env.ManagementBusConfigQueueName),
      managementBusCommandQueueName = envOrFail(Constants.Env.ManagementBusCommandQueueName),

      // Optional
      blobUrlBase = envOrElse(Constants.Env.BlobUrlBase, "https://fortiscentral.blob.core.windows.net"),
      appInsightsKey = envOrNone(Constants.Env.AppInsightsKey),
      pipelineInitWaitTimeMillis = envOrElse(Constants.Env.PipeplineInitWaitTimeMillis, Constants.PipeplineInitWaitTimeMillis.toString).toLong,
      modelsDir = envOrNone(Constants.Env.LanguageModelDir)
    )
  }

  // TODO: logging configuration should be done in log4j config file
  AppInsights.init(fortisSettings.appInsightsKey)

  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  Logger.getLogger("libinstagram").setLevel(Level.DEBUG)
  Logger.getLogger("libfacebook").setLevel(Level.DEBUG)
  Logger.getLogger("liblocations").setLevel(Level.DEBUG)

  private def createStreamingContext(): StreamingContext = {
    val batchDuration = Seconds(envOrElse(Constants.Env.SparkStreamingBatchSize, Constants.SparkStreamingBatchSizeDefault.toString).toLong)
    val conf = new SparkConf()
      .setAppName(Constants.SparkAppName)
      .setIfMissing("spark.master", Constants.SparkMasterDefault)
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "com.microsoft.partnercatalyst.fortis.spark.serialization.KryoRegistrator")
      .set("spark.kryoserializer.buffer", "128k")
      .set("spark.kryoserializer.buffer.max", "64m")
    CassandraConfig.init(conf, batchDuration, fortisSettings)

    val sparkContext = SparkContext.getOrCreate(conf)
    val ssc = new StreamingContext(sparkContext, batchDuration)
    ssc.checkpoint(fortisSettings.progressDir)
    ssc
  }

  private def attachToContext(ssc:StreamingContext): Boolean = {
    val streamProvider = StreamProviderFactory.create()

    val configManager = new CassandraConfigurationManager
    val featureServiceClient = new FeatureServiceClient(fortisSettings.featureServiceUrlBase)
    val transformContextProvider = new TransformContextProvider(configManager, featureServiceClient)

    def pipeline[T: TypeTag](name: String, analyzer: Analyzer[T]) =
      Pipeline(name, analyzer, ssc, streamProvider, transformContextProvider, configManager)

    // Attach each pipeline (aka code path)
    // 'fortisEvents' is the stream of analyzed data aggregated (union) from all pipelines
    val fortisEvents = List(
      pipeline("twitter", new TwitterAnalyzer),
      pipeline("facebookpost", new FacebookPostAnalyzer),
      pipeline("facebookcomment", new FacebookCommentAnalyzer),
      pipeline("instagram", new InstagramAnalyzer),
      pipeline("tadaweb", new TadawebAnalyzer),
      pipeline("customevents", new CustomEventAnalyzer),
      pipeline("bing", new BingAnalyzer),
      pipeline("radio", new RadioAnalyzer),
      pipeline("reddit", new RedditAnalyzer)
    ).flatten.reduceOption(_.union(_))

    if (fortisEvents.isEmpty) return false

    val session = SparkSession.builder().config(ssc.sparkContext.getConf).getOrCreate()
    CassandraEventsSink(fortisEvents.get, session)
    StreamsChangeListener(ssc, fortisSettings)

    true
  }

  // Main starts here
  while(true) {
    logInfo("Creating streaming context.")
    val ssc = StreamingContext.getOrCreate(fortisSettings.progressDir, createStreamingContext)
    var didAttach = attachToContext(ssc)
    while (!didAttach) {
      logInfo(s"No actions attached to streaming context; retrying in ${fortisSettings.pipelineInitWaitTimeMillis} milliseconds.")
      Thread.sleep(fortisSettings.pipelineInitWaitTimeMillis)
      didAttach = attachToContext(ssc)
    }
    ssc.start()
    ssc.awaitTermination()
  }

}