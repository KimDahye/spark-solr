package com.lucidworks.spark

import java.io.File
import java.util.UUID

import com.lucidworks.spark.example.ml.DateConverter
import com.lucidworks.spark.util.{EventsimUtil, SolrCloudUtil}
import org.apache.commons.io.FileUtils
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.cloud.MiniSolrCloudCluster
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.functions.udf
import org.apache.spark.{Logging, SparkConf, SparkContext}
import org.eclipse.jetty.servlet.ServletHolder
import org.junit.Assert._
import org.restlet.ext.servlet.ServerServlet
import org.scalatest.{BeforeAndAfterAll, Suite}


trait SolrCloudTestBuilder extends BeforeAndAfterAll with Logging { this: Suite =>

  @transient var cluster: MiniSolrCloudCluster = _
  @transient var cloudClient: CloudSolrClient = _
  var zkHost: String = _
  var testWorkingDir: File = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val solrXml = new File("src/test/resources/solr.xml")
    val solrXmlContents: String = TestSolrCloudClusterSupport.readSolrXml(solrXml)

    val targetDir = new File("target")
    if (!targetDir.isDirectory)
      fail("Project 'target' directory not found at :" + targetDir.getAbsolutePath)

    testWorkingDir = new File(targetDir, "scala-solrcloud-" + System.currentTimeMillis)
    if (!testWorkingDir.isDirectory)
      testWorkingDir.mkdirs

    // need the schema stuff
    val extraServlets: java.util.SortedMap[ServletHolder, String] = new java.util.TreeMap[ServletHolder, String]()

    val solrSchemaRestApi : ServletHolder = new ServletHolder("SolrSchemaRestApi", classOf[ServerServlet])
    solrSchemaRestApi.setInitParameter("org.restlet.application", "org.apache.solr.rest.SolrSchemaRestApi")
    extraServlets.put(solrSchemaRestApi, "/schema/*")

    cluster = new MiniSolrCloudCluster(1, null /* hostContext */,
      testWorkingDir.toPath, solrXmlContents, extraServlets, null /* extra filters */)
    cloudClient = new CloudSolrClient(cluster.getZkServer.getZkAddress, true)
    cloudClient.connect()

    assertTrue(!cloudClient.getZkStateReader.getClusterState.getLiveNodes.isEmpty)
    zkHost = cluster.getZkServer.getZkAddress
  }

  override def afterAll(): Unit = {
    cloudClient.close()
    cluster.shutdown()

    if (testWorkingDir != null && testWorkingDir.isDirectory) {
      FileUtils.deleteDirectory(testWorkingDir)
    }

    super.afterAll()
  }

}

trait SparkSolrContextBuilder extends BeforeAndAfterAll { this: Suite =>

  @transient var sc: SparkContext = _
  @transient var sqlContext: SQLContext = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("test")
      .set("spark.default.parallelism", "1")
    sc = new SparkContext(conf)
    sqlContext = new SQLContext(sc)
  }

  override def afterAll(): Unit = {
    sc.stop()
    super.afterAll()
  }
}

// General builder to be used by all the tests that need Solr and Spark running
trait TestSuiteBuilder extends SparkSolrFunSuite with SparkSolrContextBuilder with SolrCloudTestBuilder {}

// Builder to be used by all the tests that need Eventsim data. All test methods will re-use the same collection name
trait EventsimBuilder extends TestSuiteBuilder {

  val collectionName: String = "EventsimTest-" + UUID.randomUUID().toString

  override def beforeAll(): Unit = {
    super.beforeAll()
    SolrCloudUtil.buildCollection(zkHost, collectionName, null, numShards, cloudClient, sc)
    EventsimUtil.loadEventSimDataSet(zkHost, collectionName, sqlContext)
  }


  override def afterAll(): Unit = {
    SolrCloudUtil.deleteCollection(collectionName, cluster)
    super.afterAll()
  }

  def eventSimCount: Int = 1000

  def fieldsCount: Int = 19

  def numShards: Int = 2
}

trait MovielensBuilder extends TestSuiteBuilder {

  val moviesColName: String = "movielens_movies"
  val ratingsColName: String = "movielens_ratings"
  val userColName: String = "movielens_users"

  override def beforeAll(): Unit = {
    super.beforeAll()
    createCollections()
    MovieLensUtil.indexMovieLensDataset(sqlContext, zkHost)
  }

  override def afterAll(): Unit = {
    deleteCollections()
    super.afterAll()
  }

  def createCollections(): Unit = {
    SolrCloudUtil.buildCollection(zkHost, moviesColName, null, 1, cloudClient, sc)
    SolrCloudUtil.buildCollection(zkHost, ratingsColName, null, 1, cloudClient, sc)
//    SolrCloudUtil.buildCollection(zkHost, userColName, null, 1, cloudClient, sc)
  }

  def deleteCollections(): Unit = {
    SolrCloudUtil.deleteCollection(ratingsColName, cluster)
    SolrCloudUtil.deleteCollection(moviesColName, cluster)
//    SolrCloudUtil.deleteCollection(userColName, cluster)
  }
}

object MovieLensUtil {
  val dataDir: String = "src/test/resources/ml-100k"

  def indexMovieLensDataset(sqlContext: SQLContext, zkhost: String): Unit = {
    //    val userDF = sqlContext.read.json(dataDir + "/movielens_users.json")
    //    userDF.write.format("solr").options(Map("zkhost" -> zkhost, "collection" -> "movielens_users", "batch_size" -> "10000")).save

    val moviesDF = sqlContext.read.json(dataDir + "/movielens_movies.json")
    moviesDF.write.format("solr").options(Map("zkhost" -> zkhost, "collection" -> "movielens_movies", "batch_size" -> "10000")).save

    val ratingsDF = sqlContext.read.json(dataDir + "/movielens_ratings_10k.json")
    val dateUDF = udf(DateConverter.toISO8601(_: String))
    ratingsDF
      .withColumn("timestamp", dateUDF(ratingsDF("rating_timestamp")))
      .drop("rating_timestamp")
      .withColumnRenamed("timestamp", "rating_timestamp")
      .limit(10000)
      .write
      .format("solr")
      .options(Map("zkhost" -> zkhost, "collection" -> "movielens_ratings", "batch_size" -> "10000"))
      .save
  }
}
